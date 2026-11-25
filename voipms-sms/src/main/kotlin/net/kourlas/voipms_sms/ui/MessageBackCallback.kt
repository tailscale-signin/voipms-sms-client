package net.kourlas.voipms_sms.ui

import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.view.ActionMode

/** Let Android animate normal back navigation when neither selection nor search needs to close. */
internal class MessageBackCallback : OnBackPressedCallback(false), MenuItem.OnActionExpandListener {
    private var searchItem: MenuItem? = null
    var selection: ActionMode? = null
        set(value) {
            field = value
            isEnabled = value != null || searchItem != null
        }

    override fun onMenuItemActionExpand(item: MenuItem): Boolean {
        searchItem = item
        isEnabled = true
        return true
    }

    override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
        searchItem = null
        isEnabled = selection != null
        return true
    }

    override fun handleOnBackPressed() {
        selection?.let { it.finish() } ?: searchItem?.collapseActionView()
    }
}
