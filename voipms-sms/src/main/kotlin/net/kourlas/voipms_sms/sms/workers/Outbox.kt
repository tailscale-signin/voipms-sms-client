package net.kourlas.voipms_sms.sms.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.database.entities.OutboxPart
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.withCurrentAccount
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.utils.getMessageTexts
import net.kourlas.voipms_sms.utils.httpPostWithMultipartFormData
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class OutboxItem(val part: OutboxPart, val message: Message)
internal object Outbox {
    const val BATCH = "outbox_batch"
    const val UNDO_MS = 8000L
    private fun token() = UUID.randomUUID().toString()

    suspend fun enqueue(context: Context, thread: ConversationId, text: String, media: List<String>,
                        generation: Long, due: Long, inline: Boolean = false, asMms: Boolean = false): String {
        val batch = token()
        val part = OutboxPart(0, batch, generation, due, inline, isMms = asMms || media.isNotEmpty())
        withContext(NonCancellable) {
            check(withCurrentAccount(context, generation) {
                require(thread.did in getDids(context))
                Database.getInstance(context).insertConversationMessagesDeliveryInProgress(
                    thread, if (part.isMms) listOf(text) else getMessageTexts(context, text), media, part
                )
            } != null)
            schedule(context, part)
        }
        return batch
    }

    fun schedule(context: Context, part: OutboxPart) {
        val work = OneTimeWorkRequestBuilder<SendMessageWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay((part.dueAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(BATCH to part.batch, SendMessageWorker.ACCOUNT_GENERATION to part.generation))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sms-outbox-" + part.batch, ExistingWorkPolicy.KEEP, work)
    }

    suspend fun recover(context: Context) {
        val pending = Database.getInstance(context).outboxTransaction {
            smsDao().resetUntrackedSends()
            outboxDao().active().distinctBy { it.batch }
        }
        pending.forEach { schedule(context, it) }
    }

    suspend fun inThread(context: Context, thread: ConversationId?, generation: Long): List<OutboxItem> =
        Database.getInstance(context).outboxTransaction {
            val parts = if (thread == null) outboxDao().forAccount(generation)
                else outboxDao().forThread(thread.did, thread.contact, generation)
            parts.mapNotNull {
                smsDao().getById(it.messageId)?.let { sms ->
                    if (thread == null && net.kourlas.voipms_sms.privacy.PrivateThreads.isPrivate(context, ConversationId(sms.did, sms.contact)))
                        null else OutboxItem(it, sms.toMessage())
                }
            }
        }

    suspend fun cancel(context: Context, batch: String): Boolean =
        Database.getInstance(context).outboxTransaction {
            val parts = outboxDao().parts(batch)
            if (parts.isEmpty() || parts.any { it.state != OutboxPart.QUEUED }) return@outboxTransaction false
            parts.forEach { smsDao().deleteById(it.messageId) }
            true
        }

    /** A new batch token invalidates even a worker that started while the user was editing. */
    suspend fun edit(context: Context, batch: String, text: String, due: Long, generation: Long): Boolean {
        val replacement = Database.getInstance(context).outboxTransaction {
            val parts = outboxDao().parts(batch)
            val first = parts.firstOrNull() ?: return@outboxTransaction null
            if (parts.any { it.state != OutboxPart.QUEUED || it.generation != generation }) return@outboxTransaction null
            val original = smsDao().getById(first.messageId) ?: return@outboxTransaction null
            val model = original.toMessage()
            if (text.isBlank() && model.medias.isEmpty()) return@outboxTransaction null
            if ((first.isMms || model.medias.isNotEmpty()) && text.codePointCount(0, text.length) > net.kourlas.voipms_sms.media.MmsMedia.MAX_CAPTION) return@outboxTransaction null
            val texts = if (first.isMms || model.medias.isNotEmpty()) listOf(text) else getMessageTexts(context, text)
            val next = first.copy(batch = token(), dueAt = due)
            parts.forEach { smsDao().deleteById(it.messageId) }
            texts.forEach { segment ->
                val id = smsDao().insert(original.copy(databaseId = 0, text = segment))
                outboxDao().add(next.copy(messageId = id))
            }
            next
        } ?: return false
        schedule(context, replacement)
        return true
    }

    suspend fun failed(context: Context, part: OutboxPart, unknown: Boolean, reason: String) {
        Database.getInstance(context).outboxTransaction {
            val current = outboxDao().byId(part.messageId) ?: return@outboxTransaction
            if (current.batch != part.batch || current.state !in setOf(OutboxPart.QUEUED, OutboxPart.ATTEMPTING))
                return@outboxTransaction
            val state = if (unknown) OutboxPart.UNKNOWN else OutboxPart.REJECTED
            if (outboxDao().update(part.batch, part.messageId, state, reason) == 1) {
                // Keep the ordinary "tap to resend" path closed while receipt is uncertain.
                if (!unknown) smsDao().markMessageNotSent(part.messageId)
            }
        }
    }

    suspend fun accept(context: Context, part: OutboxPart, providerId: Long, at: Long) {
        Database.getInstance(context).outboxTransaction {
            val current = outboxDao().byId(part.messageId) ?: return@outboxTransaction
            if (current.batch != part.batch || current.state !in setOf(OutboxPart.ATTEMPTING, OutboxPart.UNKNOWN)) return@outboxTransaction
            val original = smsDao().getById(part.messageId) ?: return@outboxTransaction
            val synced = smsDao().getIdByVoipId(original.did, providerId)
            if (synced != null && synced != part.messageId) {
                check(outboxDao().byId(synced) == null) { "Provider receipt already assigned" }
                smsDao().deleteById(synced)
            }
            smsDao().markMessageSent(part.messageId, providerId, at / 1000)
            outboxDao().update(part.batch, part.messageId, OutboxPart.ACCEPTED, null)
            // Retain the provider-ID association so another identical text cannot claim the same receipt.
        }
    }

    /** User explicitly authorized a retry; a fresh token prevents older workers from claiming it. */
    suspend fun retry(context: Context, part: OutboxPart): Boolean {
        val replacement = Database.getInstance(context).outboxTransaction {
            val current = outboxDao().byId(part.messageId) ?: return@outboxTransaction null
            if (current.batch != part.batch || current.state !in setOf(OutboxPart.REJECTED, OutboxPart.UNKNOWN)) return@outboxTransaction null
            val parts = outboxDao().parts(part.batch)
            val batch = token()
            outboxDao().remove(part.batch)
            val next = current.copy(batch = batch, state = OutboxPart.QUEUED,
                dueAt = System.currentTimeMillis(), reason = null, attemptAt = null)
            parts.forEach { old ->
                outboxDao().add(if (old.messageId == part.messageId) next else old.copy(batch = batch))
            }
            smsDao().markMessageDeliveryInProgress(part.messageId)
            next
        } ?: return false
        schedule(context, replacement)
        return true
    }

    suspend fun failedMessage(context: Context, msg: Message, generation: Long): OutboxPart? =
        Database.getInstance(context).outboxTransaction {
            outboxDao().byId(msg.databaseId)?.let { return@outboxTransaction it }
            val row = smsDao().getById(msg.databaseId) ?: return@outboxTransaction null
            if (row.delivered == 1L || row.incoming == 1L) return@outboxTransaction null
            // Older databases do not distinguish rejection from a lost acknowledgement.
            OutboxPart(msg.databaseId, token(), generation, msg.date.time, false,
                OutboxPart.UNKNOWN, msg.date.time).also { outboxDao().add(it) }
        }

    suspend fun resumeRemaining(context: Context, batch: String) {
        val next = Database.getInstance(context).outboxTransaction {
            val parts = outboxDao().parts(batch)
            if (parts.none { it.state == OutboxPart.QUEUED } ||
                parts.any { it.state !in setOf(OutboxPart.QUEUED, OutboxPart.ACCEPTED) }) return@outboxTransaction null
            val token = token()
            outboxDao().remove(batch)
            parts.forEach { outboxDao().add(it.copy(batch = token)) }
            parts.first().copy(batch = token)
        }
        if (next != null) schedule(context, next)
    }

    /** Only present possible matches. A lost acknowledgement provides no unique provider identifier. */
    suspend fun candidates(context: Context, item: OutboxItem): List<IncomingMessage> {
        val attempted = item.part.attemptAt ?: return emptyList()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        return withCurrentAccount(context, item.part.generation) { account ->
            val response = httpPostWithMultipartFormData<MessagesResponse>(context, "https://voip.ms/api/v1/rest.php",
                mapOf("api_username" to account.email, "api_password" to account.password,
                    "method" to "getMMS", "did" to item.message.did, "all_messages" to "1", "timezone" to "-5",
                    "from" to day.format(Date(attempted - 120000)), "to" to day.format(Date(attempted + 300000)),
                    "limit" to "1000000"))
            if (response?.status in setOf("no_sms","no_mms")) return@withCurrentAccount emptyList()
            val rows = response?.sms?.rows
            if (response?.status != "success" || rows == null) throw IOException("Provider history unavailable")
            val formatter = messageDateFormat()
            val normalized = rows.map { row ->
                row?.incoming(item.message.did, formatter) ?: throw IOException("Provider history is incomplete")
            }
            val matches = normalized.filter { !it.isIncoming && net.kourlas.voipms_sms.utils.normalizeVoipMsDid(it.contact) ==
                net.kourlas.voipms_sms.utils.normalizeVoipMsDid(item.message.contact) &&
                it.text.orEmpty() == item.message.text && it.date.time in (attempted - 120000)..(attempted + 300000) &&
                (it.media1 != null) == item.message.medias.isNotEmpty() }.distinctBy { it.voipId }
            val assigned = Database.getInstance(context).outboxTransaction {
                matches.map { it.voipId }.chunked(500).flatMap { outboxDao().claimedIds(item.message.did, it) }.toSet()
            }
            matches.filterNot { it.voipId in assigned }
        } ?: throw IOException("Account changed")
    }
}
