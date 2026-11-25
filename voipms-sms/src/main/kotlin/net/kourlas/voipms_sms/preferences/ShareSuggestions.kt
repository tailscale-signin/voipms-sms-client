package net.kourlas.voipms_sms.preferences

import android.content.Context
import androidx.preference.PreferenceManager
import net.kourlas.voipms_sms.sms.ConversationId

internal object ShareSuggestions {
    private fun key(id: ConversationId) = "hide_share_target_${id.getId()}"

    fun enabled(context: Context, id: ConversationId): Boolean =
        !PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key(id), false)

    fun setEnabled(context: Context, id: ConversationId, enabled: Boolean) {
        val edit = PreferenceManager.getDefaultSharedPreferences(context).edit()
        if (enabled) edit.remove(key(id)) else edit.putBoolean(key(id), true)
        edit.apply()
    }
}
