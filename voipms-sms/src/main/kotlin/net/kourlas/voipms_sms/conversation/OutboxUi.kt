package net.kourlas.voipms_sms.conversation

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.entities.OutboxPart
import net.kourlas.voipms_sms.preferences.readAccountCredentials
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.sms.workers.Outbox
import net.kourlas.voipms_sms.sms.workers.OutboxItem
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

internal class OutboxUi(private val activity: FragmentActivity, private val refresh: () -> Unit = {}) {
    private fun date(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
    private fun toast(id: Int) = Toast.makeText(activity, id, Toast.LENGTH_LONG).show()
    private fun run(block: suspend () -> Unit) {
        activity.lifecycleScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { toast(R.string.outbox_action_failed) }
        }
    }

    fun pickTime(initial: Long = System.currentTimeMillis() + 3600000L, selected: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(activity, { _, year, month, day ->
            TimePickerDialog(activity, { _, hour, minute ->
                calendar.set(year, month, day, hour, minute, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                if (calendar.timeInMillis <= System.currentTimeMillis()) toast(R.string.outbox_future)
                else selected(calendar.timeInMillis)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(activity)).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
            .apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
    }

    fun show(thread: ConversationId? = null) = run {
        val generation = readAccountCredentials(activity)?.generation ?: return@run
        val groups = Outbox.inThread(activity, thread, generation).groupBy { it.part.batch }.values.toList()
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.outbox_title)
        if (groups.isEmpty()) dialog.setMessage(R.string.outbox_empty)
        else dialog.setItems(groups.map { items ->
            val row = items.firstOrNull { it.part.state !in setOf(OutboxPart.ACCEPTED, OutboxPart.QUEUED) } ?: items.first()
            val state = when (row.part.state) {
                OutboxPart.UNKNOWN -> R.string.outbox_unknown
                OutboxPart.REJECTED -> R.string.outbox_rejected
                OutboxPart.ATTEMPTING -> R.string.outbox_submitting
                else -> if (row.part.dueAt > System.currentTimeMillis()) R.string.outbox_scheduled else R.string.outbox_queued
            }
            activity.getString(state) + " · " + date(row.part.dueAt) + "\n" + row.message.contact + " · " +
                items.joinToString("") { it.message.text }.take(65)
        }.toTypedArray()) { _, which -> manage(groups[which]) }
        dialog.setNegativeButton(android.R.string.cancel, null).show()
    }

    fun reviewMessage(message: Message) = run {
        val generation = readAccountCredentials(activity)?.generation ?: return@run
        val part = Outbox.failedMessage(activity, message, generation) ?: return@run
        val items = Outbox.inThread(activity, message.conversationId, generation).filter { it.part.batch == part.batch }
        if (items.isNotEmpty()) manage(items)
    }

    private fun manage(items: List<OutboxItem>) {
        val problem = items.firstOrNull { it.part.state in setOf(OutboxPart.UNKNOWN, OutboxPart.REJECTED, OutboxPart.ATTEMPTING) }
        if (problem != null) {
            val dialog = AlertDialog.Builder(activity).setTitle(R.string.outbox_title)
                .setMessage(problem.part.reason ?: activity.getString(R.string.outbox_unconfirmed))
                .setNegativeButton(android.R.string.cancel, null)
            when (problem.part.state) {
                OutboxPart.UNKNOWN -> dialog.setPositiveButton(R.string.outbox_check) { _, _ -> checkHistory(problem) }
                OutboxPart.REJECTED -> dialog.setPositiveButton(R.string.outbox_retry) { _, _ -> retry(problem) }
            }
            dialog.show()
            return
        }
        val first = items.first()
        val fullText = items.joinToString("") { it.message.text }
        val editor = EditText(activity).apply { setText(fullText); minLines = 2; maxLines = 8 }
        AlertDialog.Builder(activity).setTitle(activity.getString(R.string.outbox_edit_title, date(first.part.dueAt)))
            .setView(editor).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.outbox_edit_time) { _, _ ->
                pickTime(maxOf(first.part.dueAt, System.currentTimeMillis() + 60000)) { due ->
                    run {
                        if (!Outbox.edit(activity, first.part.batch, editor.text.toString(), due, first.part.generation))
                            toast(R.string.outbox_too_late)
                        refresh()
                    }
                }
            }.setNeutralButton(R.string.outbox_cancel_send) { _, _ ->
                run {
                    if (!Outbox.cancel(activity, first.part.batch)) toast(R.string.outbox_too_late)
                    refresh()
                }
            }.show()
    }

    private fun retry(item: OutboxItem) = run {
        if (!Outbox.retry(activity, item.part)) toast(R.string.outbox_too_late)
        refresh()
    }

    private fun checkHistory(item: OutboxItem) = run {
        val candidates = Outbox.candidates(activity, item)
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.outbox_history)
            .setNegativeButton(android.R.string.cancel, null)
        if (candidates.isEmpty()) dialog.setMessage(R.string.outbox_no_history)
        else dialog.setItems(candidates.map { date(it.date.time) + " · #" + it.voipId + "\n" +
            it.text.orEmpty().take(100) }.toTypedArray()) { _, which ->
            val selected = candidates[which]
            AlertDialog.Builder(activity).setTitle(R.string.outbox_confirm_match)
                .setMessage(activity.getString(R.string.outbox_match_detail, selected.voipId, date(selected.date.time)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.outbox_confirm) { _, _ -> run {
                    Outbox.accept(activity, item.part, selected.voipId, selected.date.time)
                    Outbox.resumeRemaining(activity, item.part.batch)
                    refresh()
                } }.show()
        }
        dialog.setNeutralButton(R.string.outbox_retry) { _, _ ->
            AlertDialog.Builder(activity).setMessage(R.string.outbox_duplicate_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.outbox_retry) { _, _ -> retry(item) }.show()
        }.show()
    }
}
