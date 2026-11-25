package net.kourlas.voipms_sms.tools

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TimePicker
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber
import java.util.Calendar

internal class DialogForm(val host: AppCompatActivity) {
    private val fields = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
    }
    val view = ScrollView(host).apply { addView(fields) }
    fun label(resource: Int) = android.widget.TextView(host).apply {
        setText(resource)
        fields.addView(this)
    }
    fun text(hint: Int, initial: String = "", multiline: Boolean = false) = EditText(host).apply {
        setHint(hint)
        setText(initial)
        setSingleLine(!multiline)
        fields.addView(this)
    }
    fun spinner(items: List<String>, index: Int = 0) = Spinner(host).apply {
        adapter = ArrayAdapter(host, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(index)
        fields.addView(this)
    }
    fun check(label: Int, checked: Boolean = false) = CheckBox(host).apply {
        setText(label)
        isChecked = checked
        fields.addView(this)
    }
    fun time(minutes: Int) = TimePicker(host).apply {
        hour = minutes / 60
        minute = minutes % 60
        fields.addView(this)
    }
}

object LineProfiles {
    private fun prefs(context: Context) = context.getSharedPreferences("line_profiles", Context.MODE_PRIVATE)
    private val colors = intArrayOf(Color.rgb(19, 123, 166), Color.rgb(155, 94, 174), Color.rgb(36, 135, 97), Color.rgb(170, 105, 37))
    fun name(context: Context, did: String): String? = prefs(context).getString("name_$did", null)?.takeIf { it.isNotBlank() }
    fun label(context: Context, did: String): String = name(context, did)?.let { "$it · ${getFormattedPhoneNumber(did)}" } ?: getFormattedPhoneNumber(did)
    fun accent(context: Context, did: String, default: Int): Int = colors.getOrNull(prefs(context).getInt("color_$did", 0) - 1) ?: default
    fun styled(context: Context, did: String): CharSequence = SpannableString(label(context, did)).apply {
        val color = accent(context, did, Color.TRANSPARENT)
        if (color != Color.TRANSPARENT) setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    fun quiet(context: Context, did: String, now: Calendar = Calendar.getInstance()): Boolean {
        val p = prefs(context)
        if (!p.getBoolean("quiet_$did", false)) return false
        val start = p.getInt("start_$did", 22 * 60)
        val end = p.getInt("end_$did", 8 * 60)
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (start < end) minute in start until end else minute >= start || minute < end
    }
    fun edit(host: AppCompatActivity, did: String, updated: () -> Unit) {
        val p = prefs(host)
        val form = DialogForm(host)
        val name = form.text(R.string.tools_line_name, name(host, did).orEmpty())
        val color = form.spinner(host.resources.getStringArray(R.array.tools_colors).toList(), p.getInt("color_$did", 0))
        val quiet = form.check(R.string.tools_quiet, p.getBoolean("quiet_$did", false))
        val startLabel = form.label(R.string.tools_quiet_start)
        val start = form.time(p.getInt("start_$did", 22 * 60))
        val endLabel = form.label(R.string.tools_quiet_end)
        val end = form.time(p.getInt("end_$did", 8 * 60))
        start.contentDescription = host.getString(R.string.tools_quiet_start)
        end.contentDescription = host.getString(R.string.tools_quiet_end)
        fun showTimes() { for (time in listOf(startLabel, start, endLabel, end)) time.visibility = if (quiet.isChecked) android.view.View.VISIBLE else android.view.View.GONE }
        showTimes()
        quiet.setOnCheckedChangeListener { _, _ -> showTimes() }
        AlertDialog.Builder(host).setTitle(label(host, did)).setView(form.view)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.tools_save) { _, _ ->
                p.edit().putString("name_$did", name.text.toString().trim().take(40))
                    .putInt("color_$did", color.selectedItemPosition).putBoolean("quiet_$did", quiet.isChecked)
                    .putInt("start_$did", start.hour * 60 + start.minute)
                    .putInt("end_$did", end.hour * 60 + end.minute).apply()
                updated()
            }.show()
    }
}
