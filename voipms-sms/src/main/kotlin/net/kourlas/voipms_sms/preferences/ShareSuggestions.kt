package net.kourlas.voipms_sms.preferences

import androidx.core.content.edit
import android.content.Context
import androidx.preference.PreferenceManager
import net.kourlas.voipms_sms.sms.ConversationId

internal object ShareSuggestions {
    private fun key(id: ConversationId) = "hide_share_target_${id.getId()}"

    fun enabled(context: Context, id: ConversationId): Boolean =
        !PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key(id), false)

    fun setEnabled(context: Context, id: ConversationId, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            if (enabled) remove(key(id)) else putBoolean(key(id), true)
        }
    }
}
