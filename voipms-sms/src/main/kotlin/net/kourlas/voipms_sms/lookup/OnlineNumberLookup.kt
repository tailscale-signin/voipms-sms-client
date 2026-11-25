package net.kourlas.voipms_sms.lookup

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.PersistableBundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.preferences.currentDirectoryAccountGeneration
import net.kourlas.voipms_sms.utils.getContactName
import net.kourlas.voipms_sms.utils.getFormattedPhoneNumber
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal object OnlineNumberLookup {
    internal sealed interface Label {
        data class Searching(val progressText: Int) : Label
        data class Match(val name: String) : Label
    }

    private class Session(val generation: Long) {
        var engine = CallerIdEngine()
        val accounts = mutableMapOf<LookupAccount, LookupCredentials>()
        val jobs = mutableMapOf<String, Job>()
        val online = mutableSetOf<String>()
        val dialogs = mutableSetOf<AlertDialog>()
        val selected = mutableMapOf<CallerSource, Boolean>()
        val cards = object : LinkedHashMap<String, List<SourceResult>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SourceResult>>) = size > 128
        }

        fun recent(number: String): List<SourceResult> {
            val now = System.currentTimeMillis()
            return cards[number].orEmpty().filter {
                val ttl = if (it.status == LookupStatus.FOUND) 900_000L else 120_000L
                now - it.time in 0 until ttl
            }
        }

        fun resetResults() {
            jobs.values.toList().forEach { it.cancel() }
            jobs.clear()
            online.clear()
            dialogs.toList().forEach { it.dismiss() }
            cards.clear()
            engine = CallerIdEngine()
        }
    }

    private var active: Session? = null
    private var accountListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val revision = MutableStateFlow(0L)
    val updates = revision.asStateFlow()

    private fun session(context: Context): Session {
        val app = context.applicationContext
        if (accountListener == null) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                if (active?.generation != currentDirectoryAccountGeneration(app)) session(app)
            }
            PreferenceManager.getDefaultSharedPreferences(app).registerOnSharedPreferenceChangeListener(listener)
            accountListener = listener
        }
        val generation = currentDirectoryAccountGeneration(app)
        active?.takeIf { it.generation == generation }?.let { return it }
        active?.let { it.accounts.clear(); it.resetResults() }
        return Session(generation).also { active = it; revision.value++ }
    }

    fun available(raw: String, localName: String?) = localName == null && WikidataDirectory.number(raw) != null

    fun label(context: Context, raw: String, localName: String?): Label? {
        if (localName != null) return null
        val number = WikidataDirectory.number(raw) ?: return null
        val state = session(context)
        if (number in state.online) return Label.Searching(R.string.cid_searching)
        val summary = summarizeCaller(state.recent(number))
        if (summary.conflictingNames) return Label.Match(context.getString(R.string.cid_conflict))
        val fact = summary.fact ?: return null
        val sources = summary.sources.joinToString(", ") { it.attribution }
        return Label.Match(context.getString(R.string.cid_subtitle, context.getString(fact.kind.title), fact.value, sources))
    }

    fun blueNumber(context: Context, raw: String, toolbar: Boolean = false): SpannableString {
        val color = if (toolbar) R.color.online_lookup_toolbar_number else R.color.online_lookup_number
        return SpannableString(getFormattedPhoneNumber(raw)).apply {
            setSpan(ForegroundColorSpan(ContextCompat.getColor(context, color)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun description(context: Context, label: Label) = when (label) {
        is Label.Searching -> context.getString(label.progressText)
        is Label.Match -> label.name
    }

    fun offer(activity: AppCompatActivity, raw: String) {
        val number = WikidataDirectory.number(raw) ?: return
        if (getContactName(activity, raw) != null) return
        val state = session(activity)
        val region = CallerSource.region(number)
        val choices = mutableListOf<Pair<Int, () -> Unit>>()
        choices += R.string.cid_search to { choose(activity, number, state) }
        if (state.recent(number).isNotEmpty()) choices += R.string.cid_reopen to {
            showCard(activity, state, number, state.recent(number))
        }
        choices += R.string.cid_accounts to { accounts(activity, number, state) }
        fun shortcut(title: Int, notice: Int, action: Int = title, proceed: () -> Unit) {
            choices += title to {
                AlertDialog.Builder(activity).setTitle(title)
                    .setMessage(activity.getString(notice, getFormattedPhoneNumber(number)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(action) { _, _ -> if (eligible(activity, number, state)) proceed() }.show()
            }
        }
        if (CallerSource.LOCAL_CALLING_GUIDE.supports(number, region)) {
            shortcut(R.string.lookup_origin, R.string.lookup_origin_consent) {
                search(activity, number, state, listOf(CallerSource.LOCAL_CALLING_GUIDE))
            }
        }
        shortcut(R.string.lookup_directory, R.string.lookup_consent) {
            search(activity, number, state, listOf(CallerSource.WIKIDATA))
        }
        if (CallerSource.SHADYCALL.supports(number, region)) {
            shortcut(R.string.lookup_reports, R.string.lookup_reports_consent) {
                search(activity, number, state, listOf(CallerSource.SHADYCALL))
            }
        }
        for (source in listOf(CallerSource.TELNYX_CARRIER, CallerSource.TELNYX_IDENTITY)) {
            if (!source.supports(number, region)) continue
            val notice = if (source == CallerSource.TELNYX_IDENTITY) R.string.telnyx_identity_notice else R.string.telnyx_carrier_notice
            shortcut(source.title, notice, R.string.telnyx_continue) {
                TelnyxLookup.requestKey(activity) { token ->
                    if (eligible(activity, number, state)) {
                        search(activity, number, state, listOf(source),
                            mapOf(LookupAccount.TELNYX to LookupCredentials(mapOf(AccountField.KEY to token))))
                    }
                }
            }
        }
        shortcut(R.string.lookup_truecaller, R.string.lookup_truecaller_consent, R.string.lookup_copy_open) {
            openTruecaller(activity, number)
        }
        AlertDialog.Builder(activity).setTitle(activity.getString(R.string.lookup_number_title, getFormattedPhoneNumber(raw)))
            .setItems(choices.map { activity.getString(it.first) }.toTypedArray()) { _, index ->
                if (eligible(activity, number, state)) choices[index].second()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun eligible(activity: AppCompatActivity, number: String, state: Session) =
        !activity.isFinishing && !activity.isDestroyed && session(activity) === state && getContactName(activity, number) == null

    private fun accounts(activity: AppCompatActivity, number: String, state: Session) {
        manageLookupAccounts(activity, state.accounts.keys.toSet()) { account, credential ->
            if (eligible(activity, number, state)) {
                if (credential == null) state.accounts.remove(account) else state.accounts[account] = credential
                state.resetResults()
                revision.value++
                choose(activity, number, state)
            }
        }
    }

    private fun choose(activity: AppCompatActivity, number: String, state: Session) {
        val region = CallerSource.region(number)
        val sources = CallerSource.entries.filter {
            it.supports(number, region) && (it.account == null || it.account in state.accounts)
        }
        val checked = sources.map { state.selected[it] ?: (it.account == null) }.toBooleanArray()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.cid_choose, getFormattedPhoneNumber(number)))
            .setMultiChoiceItems(sources.map { activity.getString(it.title) }.toTypedArray(), checked) { prompt, i, value ->
                checked[i] = value
                (prompt as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked.any { it }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.cid_accounts) { _, _ -> if (eligible(activity, number, state)) accounts(activity, number, state) }
            .setPositiveButton(R.string.cid_go) { _, _ ->
                if (eligible(activity, number, state)) {
                    sources.forEachIndexed { i, source -> state.selected[source] = checked[i] }
                    val selected = sources.filterIndexed { index, _ -> checked[index] }
                    if (selected == listOf(CallerSource.DEVICE)) search(activity, number, state, selected)
                    else confirm(activity, number, state, selected)
                }
            }.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked.any { it }
    }

    private fun confirm(activity: AppCompatActivity, number: String, state: Session, selected: List<CallerSource>) {
        val details = selected.groupBy {
            when (it) {
                CallerSource.DEVICE -> R.string.cid_scope_device
                CallerSource.CANADA -> R.string.cid_scope_canada
                CallerSource.LOCAL_CALLING_GUIDE -> R.string.cid_scope_lcg
                CallerSource.PHONEBLOCK -> R.string.cid_scope_phoneblock
                else -> R.string.cid_scope_full
            }
        }.entries.joinToString("\n\n") { (scope, sources) ->
            activity.getString(scope) + "\n" + sources.joinToString(", ") { activity.getString(it.title) }
        }
        AlertDialog.Builder(activity).setTitle(activity.getString(R.string.cid_confirm, getFormattedPhoneNumber(number)))
            .setMessage(activity.getString(R.string.cid_disclosure) + "\n\n" + details)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cid_go) { _, _ ->
                if (eligible(activity, number, state)) search(activity, number, state, selected)
            }.show()
    }

    private fun search(activity: AppCompatActivity, number: String, state: Session, sources: List<CallerSource>,
                       credentials: Map<LookupAccount, LookupCredentials> = state.accounts.toMap()) {
        if (number in state.jobs || sources.isEmpty()) return
        val engine = state.engine
        val pending = sources.toMutableSet()
        lateinit var card: AlertDialog
        val job = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            engine.search(number, sources, credentials, Locale.getDefault(), onResult = result@{ result ->
                if (state !== active || state.engine !== engine || !eligible(activity, number, state)) {
                    card.dismiss()
                    return@result
                }
                pending.remove(result.source)
                val merged = (state.recent(number).filter { it.source != result.source } + result)
                    .sortedBy { it.source.ordinal }
                state.cards[number] = merged
                if (card.isShowing) card.setMessage(cardDetails(activity, merged, pending))
                revision.value++
            })
        }
        card = showCard(activity, state, number, state.recent(number), pending) { job.cancel() }
        state.jobs[number] = job
        if (sources.any { it != CallerSource.DEVICE }) state.online += number
        revision.value++
        job.invokeOnCompletion {
            if (state.jobs[number] === job) {
                state.jobs.remove(number)
                state.online.remove(number)
            }
            revision.value++
        }
        job.start()
    }

    private fun cardDetails(activity: AppCompatActivity, rows: List<SourceResult>, pending: Set<CallerSource>): CharSequence {
        val details = SpannableStringBuilder()
        fun heading(text: String) {
            val start = details.length
            details.append(text)
            details.setSpan(StyleSpan(Typeface.BOLD), start, details.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val summary = summarizeCaller(rows)
        if (summary.conflictingNames) {
            heading(activity.getString(R.string.cid_conflict))
            details.append("\n\n")
        } else if (pending.isEmpty() && rows.none { row -> row.facts.any { it.kind == FactKind.NAME } }) {
            details.append(activity.getString(R.string.cid_no_names)).append("\n\n")
        }
        val times = DateFormat.getTimeInstance(DateFormat.SHORT)
        for (row in rows) {
            heading(activity.getString(row.source.title))
            details.append(" · ").append(times.format(Date(row.time)))
            if (row.status != LookupStatus.FOUND) details.append("\n").append(activity.getString(row.status.title))
            for (fact in row.facts) details.append("\n").append(activity.getString(R.string.cid_line_format,
                activity.getString(fact.kind.title), fact.value))
            details.append("\n\n")
        }
        if (pending.isNotEmpty()) {
            heading(activity.getString(R.string.cid_searching))
            details.append("\n").append(pending.joinToString(", ") { it.attribution }).append("\n\n")
        }
        return details.append(activity.getString(R.string.cid_card_note))
    }

    private fun showCard(activity: AppCompatActivity, state: Session, number: String, rows: List<SourceResult>,
                         pending: Set<CallerSource> = emptySet(), onClose: () -> Unit = {}): AlertDialog {
        return AlertDialog.Builder(activity).setTitle(activity.getString(R.string.cid_card, getFormattedPhoneNumber(number)))
            .setMessage(cardDetails(activity, rows, pending)).setPositiveButton(android.R.string.ok, null)
            .apply {
                if (CallerSource.SHADYCALL in pending || rows.any { it.source == CallerSource.SHADYCALL }) {
                    setNeutralButton(R.string.lookup_reports_attribution) { _, _ ->
                        try {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, ShadyCallReputation.sourceUrl(number).toUri()))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(activity, R.string.lookup_no_browser, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.show().also { card ->
                state.dialogs += card
                val observer = object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) { card.dismiss() }
                }
                activity.lifecycle.addObserver(observer)
                card.setOnDismissListener {
                    state.dialogs.remove(card)
                    activity.lifecycle.removeObserver(observer)
                    onClose()
                }
            }
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
