package net.kourlas.voipms_sms.diagnostics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import net.kourlas.voipms_sms.conversation.ConversationActivity
import net.kourlas.voipms_sms.conversations.ConversationsActivity
import net.kourlas.voipms_sms.newConversation.NewConversationActivity

internal object DebugLifecycle : Application.ActivityLifecycleCallbacks {
    fun install(application: Application) {
        val store = DebugJournal.store(application)
        application.registerActivityLifecycleCallbacks(this)
        DebugJournal.record(application, DiagnosticAction.APP, DiagnosticStep.STARTED)
        val original = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { store.fatal(error) } catch (_: Throwable) {
                // Even a failing diagnostic disk write must pass the original crash to Android.
            } finally { original.uncaughtException(thread, error) }
        }
    }

    private fun mark(activity: Activity, stage: DiagnosticStep) {
        if (!DebugJournal.enabled(activity)) return
        val action = when (activity) {
            is ConversationsActivity -> DiagnosticAction.INBOX_SCREEN
            is ConversationActivity -> DiagnosticAction.THREAD_SCREEN
            is NewConversationActivity -> DiagnosticAction.COMPOSE_SCREEN
            else -> if (activity.javaClass.name.startsWith("net.kourlas.voipms_sms.preferences."))
                DiagnosticAction.SETTINGS_SCREEN else DiagnosticAction.OTHER_SCREEN
        }
        DebugJournal.record(activity, action, stage)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = mark(activity, DiagnosticStep.CREATED)
    override fun onActivityStarted(activity: Activity) = mark(activity, DiagnosticStep.STARTED)
    override fun onActivityResumed(activity: Activity) = mark(activity, DiagnosticStep.RESUMED)
    override fun onActivityPaused(activity: Activity) = mark(activity, DiagnosticStep.PAUSED)
    override fun onActivityStopped(activity: Activity) = mark(activity, DiagnosticStep.STOPPED)
    override fun onActivityDestroyed(activity: Activity) = mark(activity, DiagnosticStep.DESTROYED)
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
