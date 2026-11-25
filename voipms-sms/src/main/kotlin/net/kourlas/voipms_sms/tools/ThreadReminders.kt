package net.kourlas.voipms_sms.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber
import java.util.Calendar
import java.util.concurrent.TimeUnit

object ThreadReminders {
    private const val NEXT_INBOUND = -1L
    private fun preferences(c: Context) = c.getSharedPreferences("thread_reminders", Context.MODE_PRIVATE)
    private fun workName(id: ConversationId) = "remind_${id.getId()}"
    fun observe(c: Context, listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener, enabled: Boolean) {
        if (enabled) preferences(c).registerOnSharedPreferenceChangeListener(listener)
        else preferences(c).unregisterOnSharedPreferenceChangeListener(listener)
    }
    fun hidden(c: Context, id: ConversationId): Boolean {
        val due = preferences(c).getLong(id.getId(), 0)
        return due == NEXT_INBOUND || due > System.currentTimeMillis()
    }
    private fun clear(c: Context, id: ConversationId) {
        preferences(c).edit().remove(id.getId()).remove("since_${id.getId()}").apply()
    }
    fun inbound(c: Context, id: ConversationId, timestamp: Long) {
        val p = preferences(c)
        if (p.getLong(id.getId(), 0) == NEXT_INBOUND && timestamp >= p.getLong("since_${id.getId()}", Long.MAX_VALUE)) clear(c, id)
    }
    fun choose(host: AppCompatActivity, id: ConversationId) {
        val choices = host.resources.getStringArray(R.array.tools_snooze_intervals)
        AlertDialog.Builder(host).setTitle(R.string.tools_remind).setItems(choices) { _, index ->
            val now = System.currentTimeMillis()
            if (index == 3) {
                val selected = Calendar.getInstance()
                android.app.DatePickerDialog(host, { _, year, month, day ->
                    selected.set(year, month, day)
                    android.app.TimePickerDialog(host, { _, hour, minute ->
                        selected.set(Calendar.HOUR_OF_DAY, hour)
                        selected.set(Calendar.MINUTE, minute)
                        selected.set(Calendar.SECOND, 0)
                        selected.set(Calendar.MILLISECOND, 0)
                        if (selected.timeInMillis > System.currentTimeMillis()) set(host, id, selected.timeInMillis)
                        else android.widget.Toast.makeText(host, R.string.tools_future, android.widget.Toast.LENGTH_SHORT).show()
                    }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(host)).show()
                }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).apply {
                    datePicker.minDate = now
                }.show()
                return@setItems
            }
            val due = when (index) {
                0 -> now + TimeUnit.HOURS.toMillis(1)
                1 -> Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                2 -> NEXT_INBOUND
                else -> 0L
            }
            set(host, id, due)
            android.widget.Toast.makeText(host, choices[index], android.widget.Toast.LENGTH_SHORT).show()
        }.show()
    }
    private fun set(host: AppCompatActivity, id: ConversationId, due: Long) {
            val now = System.currentTimeMillis()
            val work = WorkManager.getInstance(host)
            if (due == 0L) clear(host, id) else preferences(host).edit()
                .putLong(id.getId(), due).putLong("since_${id.getId()}", now / 1000).apply()
            if (due > 0) {
                val job = OneTimeWorkRequestBuilder<ThreadReminderWorker>()
                    .setInitialDelay(due - now, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("did" to id.did, "contact" to id.contact, "due" to due)).build()
                work.enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, job)
            } else work.cancelUniqueWork(workName(id))
    }
    fun showSnoozed(host: AppCompatActivity) {
        val dids = getDids(host, onlyShowInConversationsView = true)
        val ids = preferences(host).all.keys.filterNot { it.startsWith("since_") }.mapNotNull {
            val fields = it.split("_", limit = 2)
            if (fields.size == 2 && fields[0] in dids) ConversationId(fields[0], fields[1]) else null
        }.filter { hidden(host, it) && !net.kourlas.voipms_sms.privacy.PrivateThreads.isPrivate(host, it) }
        val dialog = AlertDialog.Builder(host).setTitle(R.string.tools_snoozed)
        if (ids.isEmpty()) dialog.setMessage(R.string.tools_none)
        else dialog.setItems(ids.map { "${LineProfiles.label(host, it.did)} · ${getFormattedPhoneNumber(it.contact)}" }.toTypedArray()) { _, i ->
            host.startActivity(intent(host, ids[i]))
        }
        dialog.setNegativeButton(android.R.string.cancel, null).show()
    }
    fun intent(c: Context, id: ConversationId): Intent = Intent(c, ConversationActivity::class.java)
        .putExtra(c.getString(R.string.conversation_did), id.did)
        .putExtra(c.getString(R.string.conversation_contact), id.contact)
    fun notify(c: Context, id: ConversationId, due: Long) {
        if (preferences(c).getLong(id.getId(), 0) != due || due <= 0) return
        clear(c, id)
        if (id.did !in getDids(c, onlyShowInConversationsView = true)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = c.getSystemService(NotificationManager::class.java) ?: return
        val channel = "conversation_reminders"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, c.getString(R.string.tools_remind), NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(c, 0, intent(c, id).setData(android.net.Uri.Builder().scheme("sms-reminder").authority("thread").appendPath(id.getId()).build()), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(c, channel).setSmallIcon(R.drawable.ic_chat_toolbar_24dp)
            .setContentTitle(c.getString(R.string.tools_reminder_due)).setContentText(c.getString(R.string.tools_open_thread))
            .setContentIntent(tap).setAutoCancel(true).setSilent(LineProfiles.quiet(c, id.did))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        manager.notify(id.getId(), 1, notification)
    }
}
class ThreadReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = ConversationId(inputData.getString("did") ?: return Result.failure(), inputData.getString("contact") ?: return Result.failure())
        ThreadReminders.notify(applicationContext, id, inputData.getLong("due", 0))
        return Result.success()
    }
}
