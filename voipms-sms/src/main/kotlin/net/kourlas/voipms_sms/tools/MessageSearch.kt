package net.kourlas.voipms_sms.tools

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.database.entities.Sms
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.InboxRules
import net.kourlas.voipms_sms.utils.getContactName
import java.text.DateFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "message_search")
data class MessageSearchIndex(val body: String, val ocr: String)

object SearchIndex {
    fun install(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TRIGGER IF NOT EXISTS search_added AFTER INSERT ON sms BEGIN INSERT INTO message_search(rowid,body,ocr) VALUES(NEW.DatabaseId,NEW.Text,''); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS search_changed AFTER UPDATE OF Text ON sms BEGIN UPDATE message_search SET body=NEW.Text WHERE rowid=NEW.DatabaseId; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS search_media_changed AFTER UPDATE OF MediaJson, Media1, Media2, Media3 ON sms WHEN NEW.MediaJson != OLD.MediaJson OR NEW.Media1 != OLD.Media1 OR NEW.Media2 != OLD.Media2 OR NEW.Media3 != OLD.Media3 BEGIN UPDATE message_search SET ocr='' WHERE rowid=NEW.DatabaseId; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS search_removed AFTER DELETE ON sms BEGIN DELETE FROM message_search WHERE rowid=OLD.DatabaseId; END")
        db.execSQL("INSERT INTO message_search(rowid,body,ocr) SELECT DatabaseId,Text,'' FROM sms WHERE DatabaseId NOT IN (SELECT rowid FROM message_search)")
    }
}

@Dao
interface MessageSearchDao {
    @RawQuery suspend fun search(query: SupportSQLiteQuery): List<Sms>
}

internal data class SearchFilters(
    val words: String, val contact: String, val dids: List<String>, val after: Long?, val until: Long?,
    val links: Boolean, val media: Boolean, val folder: Int
) {
    val digits: String? = contact.takeIf { it.isNotBlank() && it.all { char -> char.isDigit() || char in " +()-." } }
        ?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }
    fun query(offset: Int): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val terms = Regex("[\\p{L}\\p{N}]+").findAll(words).map { "\"${it.value}*\"" }.take(16).toList()
        val sql = StringBuilder("SELECT s.* FROM sms AS s")
        if (terms.isNotEmpty()) sql.append(" JOIN message_search ON message_search.rowid=s.DatabaseId")
        sql.append(" WHERE s.Did IN (").append(dids.joinToString(",") { "?" }).append(")")
        args.addAll(dids)
        if (words.isNotBlank() && terms.isEmpty()) { sql.append(" AND instr(s.Text,?)>0"); args.add(words.trim()) }
        if (terms.isNotEmpty()) { sql.append(" AND message_search MATCH ?"); args.add(terms.joinToString(" ")) }
        digits?.let { sql.append(" AND instr(s.Contact,?)>0"); args.add(it.removePrefix("1").takeIf { short -> it.length == 11 && short.length == 10 } ?: it) }
        if (after != null) { sql.append(" AND s.Date>=?"); args.add(after) }
        if (until != null) { sql.append(" AND s.Date<?"); args.add(until) }
        if (media) sql.append(" AND (s.Media1<>'' OR s.Media2<>'' OR s.Media3<>'' OR s.MediaJson<>'')")
        if (links) sql.append(" AND (s.Text LIKE '%https://%' OR s.Text LIKE '%http://%' OR s.Text LIKE '%www.%')")
        sql.append(" ORDER BY s.Date DESC,s.DatabaseId DESC LIMIT 100 OFFSET ?")
        args.add(offset)
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }
}

internal object MessageSearch {
    const val ANCHOR = "search_message_id"
    fun open(host: AppCompatActivity) {
        val dids = getDids(host, onlyShowInConversationsView = true).sorted()
        if (dids.isEmpty()) return
        val form = DialogForm(host)
        val words = form.text(R.string.tools_search_words)
        val contact = form.text(R.string.tools_contact)
        val line = form.spinner(listOf(host.getString(R.string.conversations_all_dids)) + dids.map { LineProfiles.label(host, it) })
        val folder = form.spinner(host.resources.getStringArray(R.array.tools_search_folders).toList())
        val from = form.text(R.string.tools_since)
        val to = form.text(R.string.tools_through)
        val links = form.check(R.string.tools_links)
        val media = form.check(R.string.tools_media)
        val dialog = AlertDialog.Builder(host).setTitle(R.string.tools_search)
            .setView(form.view).setPositiveButton(R.string.tools_search, null).setNegativeButton(android.R.string.cancel, null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
            fun parse(v: android.widget.EditText): Calendar? {
                val text = v.text.toString().trim()
                if (text.isEmpty()) return null
                val end = ParsePosition(0)
                val date = fmt.parse(text, end)
                if (date == null || end.index != text.length || !Regex("\\d{4}-\\d{2}-\\d{2}").matches(text)) { v.error = host.getString(R.string.tools_date_error); return null }
                return Calendar.getInstance().apply { time = date }
            }
            val lower = parse(from)
            if (lower == null && from.text.isNotBlank()) return@setOnClickListener
            val upper = parse(to)
            if (upper == null && to.text.isNotBlank()) return@setOnClickListener
            upper?.add(Calendar.DAY_OF_MONTH, 1)
            if (lower != null && upper != null && lower >= upper) { to.error = host.getString(R.string.tools_date_error); return@setOnClickListener }
            val selected = dids.getOrNull(line.selectedItemPosition - 1)
            val filters = SearchFilters(words.text.toString(), contact.text.toString().trim(), selected?.let(::listOf) ?: dids,
                lower?.timeInMillis?.div(1000), upper?.timeInMillis?.div(1000), links.isChecked, media.isChecked, folder.selectedItemPosition)
            dialog.dismiss()
            results(host, filters, 0)
        }
    }
    private fun results(host: AppCompatActivity, filters: SearchFilters, initialOffset: Int) {
        host.lifecycleScope.launch {
            val db = Database.getInstance(host)
            val names = mutableMapOf<String, String>()
            val contactMatches = mutableMapOf<String, Boolean>()
            val phrases = net.kourlas.voipms_sms.privacy.SpamReview.phrases(host)
            val (rows, next) = try { withContext(Dispatchers.IO) {
                val matched = mutableListOf<Sms>()
                var offset = initialOffset
                val folders = if (filters.folder > 0) db.inboxPreviews(filters.dids.toSet(), "", filters.folder == 3, names).associate {
                    val msg = it.message
                    val other = it.otherFolder ?: (InboxRules.reason(it.latestIncoming, it.hasReply, getContactName(host, msg.contact, names) != null, phrases) != null)
                    msg.conversationId to other
                } else emptyMap()
                var more = true
                while (matched.size < 40 && more) {
                    val page = db.searchMessages(filters.query(offset))
                    more = page.size == 100
                    for ((idx, msg) in page.withIndex()) {
                        offset++
                        val key = ConversationId(msg.did, msg.contact)
                        val folderMatch = filters.folder == 0 || (key in folders && (filters.folder == 3 || folders[key] == (filters.folder == 2)))
                        if (!net.kourlas.voipms_sms.privacy.PrivateThreads.isPrivate(host, key) && folderMatch &&
                            (filters.contact.isBlank() || filters.digits != null || contactMatches.getOrPut(msg.contact) {
                                getContactName(host, msg.contact, names)?.contains(filters.contact, true) == true
                            })) matched.add(msg)
                        if (matched.size == 40) { more = more || idx < page.lastIndex; break }
                    }
                }
                matched to if (more) offset else null
            } } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { android.widget.Toast.makeText(host, R.string.tools_search_failed, android.widget.Toast.LENGTH_LONG).show(); return@launch }
            val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val labels = rows.map { m ->
                "${names[m.contact] ?: m.contact} · ${LineProfiles.label(host, m.did)}\n${format.format(Date(m.date * 1000))}  ${m.text.take(140)}"
            }
            val dialog = AlertDialog.Builder(host).setTitle(R.string.tools_search_results).setNegativeButton(android.R.string.cancel, null)
            if (rows.isEmpty()) dialog.setMessage(R.string.tools_none) else dialog.setItems(labels.toTypedArray()) { _, i ->
                val m = rows[i]
                host.startActivity(ThreadReminders.intent(host, ConversationId(m.did, m.contact)).putExtra(ANCHOR, m.databaseId))
            }
            if (next != null) dialog.setPositiveButton(R.string.tools_next_page) { _, _ -> results(host, filters, next) }
            dialog.show()
        }
    }
}
