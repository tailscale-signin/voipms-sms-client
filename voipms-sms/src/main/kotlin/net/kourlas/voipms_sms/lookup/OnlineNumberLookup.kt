package net.kourlas.voipms_sms.lookup

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.preferences.currentDirectoryAccountGeneration
import net.kourlas.voipms_sms.utils.getContactName
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber

/** Session-only directory labels. None are saved to contacts, backups, or the SMS database. */
internal object OnlineNumberLookup {
    internal sealed interface Label {
        data class Searching(val progressText: Int) : Label
        data class Match(val name: String) : Label
    }

    private enum class Service(val title: Int, val notice: Int, val action: Int) {
        WIKIDATA(R.string.lookup_directory, R.string.lookup_consent, R.string.lookup_directory),
        SHADYCALL(R.string.lookup_reports, R.string.lookup_reports_consent, R.string.lookup_reports),
        TRUECALLER(R.string.lookup_truecaller, R.string.lookup_truecaller_consent, R.string.lookup_copy_open)
    }

    private data class Key(val generation: Long, val number: String)
    private val labels = mutableMapOf<Key, Label>()
    private val revision = MutableStateFlow(0L)
    val updates = revision.asStateFlow()

    fun available(raw: String, localName: String?) = localName == null && WikidataDirectory.number(raw) != null

    fun label(context: Context, raw: String, localName: String?): Label? {
        if (localName != null) return null
        val number = WikidataDirectory.number(raw) ?: return null
        return labels[Key(currentDirectoryAccountGeneration(context), number)]
    }

    fun blueNumber(context: Context, raw: String, toolbar: Boolean = false): SpannableString {
        val color = if (toolbar) R.color.online_lookup_toolbar_number else R.color.online_lookup_number
        return SpannableString(getFormattedPhoneNumber(raw)).apply {
            setSpan(ForegroundColorSpan(ContextCompat.getColor(context, color)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun description(context: Context, label: Label) = when (label) {
        is Label.Searching -> context.getString(label.progressText)
        is Label.Match -> context.getString(R.string.lookup_result, label.name)
    }

    fun offer(activity: AppCompatActivity, raw: String) {
        val number = WikidataDirectory.number(raw) ?: return
        if (getContactName(activity, raw) != null) return
        val key = Key(currentDirectoryAccountGeneration(activity), number)
        val choices = Service.entries.filter { it != Service.SHADYCALL || ShadyCallReputation.supports(number) }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.lookup_number_title, getFormattedPhoneNumber(raw)))
            .setItems(choices.map { activity.getString(it.title) }.toTypedArray()) { _, i ->
                val service = choices[i]
                AlertDialog.Builder(activity)
                    .setTitle(service.title)
                    .setMessage(activity.getString(service.notice, getFormattedPhoneNumber(raw)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(service.action) { _, _ ->
                        if (currentDirectoryAccountGeneration(activity) == key.generation && getContactName(activity, raw) == null) {
                            if (service == Service.TRUECALLER) openTruecaller(activity, number)
                            else search(activity, key, service)
                        }
                    }
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun search(activity: AppCompatActivity, key: Key, service: Service) {
        if (labels[key] is Label.Searching) return
        activity.lifecycleScope.launch {
            val progress = if (service == Service.WIKIDATA) R.string.lookup_searching else R.string.lookup_reports_searching
            val previous = labels.put(key, Label.Searching(progress))
            revision.value++
            var result = previous
            try {
                if (service == Service.WIKIDATA) {
                    val name = WikidataDirectory.search(key.number)
                    result = name?.let { Label.Match(it) }
                    if (name == null && currentDirectoryAccountGeneration(activity) == key.generation) {
                        Toast.makeText(activity, R.string.lookup_no_match, Toast.LENGTH_LONG).show()
                    }
                } else {
                    val report = ShadyCallReputation.search(key.number)
                    if (currentDirectoryAccountGeneration(activity) == key.generation &&
                        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        showReports(activity, key.number, report)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (currentDirectoryAccountGeneration(activity) == key.generation) {
                    Toast.makeText(activity, R.string.lookup_unavailable, Toast.LENGTH_LONG).show()
                }
            } finally {
                labels.remove(key)
                if (result != null && currentDirectoryAccountGeneration(activity) == key.generation) {
                    labels[key] = result
                }
                while (labels.size > 128) {
                    val oldest = labels.entries.firstOrNull { it.value is Label.Match } ?: break
                    labels.remove(oldest.key)
                }
                revision.value++
            }
        }
    }

    private fun showReports(activity: AppCompatActivity, number: String, report: ShadyCallReport?) {
        val details = SpannableStringBuilder(blueNumber(activity, number)).append("\n\n")
        if (report == null) {
            details.append(activity.getString(R.string.lookup_reports_none))
        } else {
            details.append(activity.getString(R.string.lookup_report_counts, report.complaints, report.communityReports))
        }
        details.append("\n\n").append(activity.getString(R.string.lookup_reports_caution))
        AlertDialog.Builder(activity)
            .setTitle(R.string.lookup_reports_title)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.lookup_reports_attribution) { _, _ ->
                try {
                    val page = report?.sourceUrl ?: ShadyCallReputation.sourceUrl(number)
                    activity.startActivity(Intent(Intent.ACTION_VIEW, page.toUri()))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(activity, R.string.lookup_no_browser, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun openTruecaller(activity: AppCompatActivity, number: String) {
        // The official public search form accepts a pasted number. No private API or login token is used.
        val page = "https://www.truecaller.com/reverse-phone-number-lookup".toUri()
        val intent = Intent(Intent.ACTION_VIEW, page)
        try {
            val clip = ClipData.newPlainText(activity.getString(R.string.conversation_contact_clipboard_description), number)
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.lookup_no_browser, Toast.LENGTH_LONG).show()
        }
    }
}
