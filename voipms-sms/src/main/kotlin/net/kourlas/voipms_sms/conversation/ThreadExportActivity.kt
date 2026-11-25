package net.kourlas.voipms_sms.conversation

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.BuildConfig
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.database.Database
import net.kourlas.voipms_sms.demo.getConversationDemoMessages
import net.kourlas.voipms_sms.diagnostics.DebugJournal
import net.kourlas.voipms_sms.diagnostics.DebugTrace
import net.kourlas.voipms_sms.diagnostics.DiagnosticAction
import net.kourlas.voipms_sms.diagnostics.DiagnosticStep
import net.kourlas.voipms_sms.preferences.currentDirectoryAccountGeneration
import net.kourlas.voipms_sms.preferences.getDids
import net.kourlas.voipms_sms.preferences.withCurrentAccount
import net.kourlas.voipms_sms.privacy.PrivateThreads
import net.kourlas.voipms_sms.sms.ConversationId
import net.kourlas.voipms_sms.sms.Message
import net.kourlas.voipms_sms.utils.DocumentOutput
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.Channels

/** Keeps picker grants and private authorization separate from switching threads or message-list filters. */
class ThreadExportActivity : AppCompatActivity() {
    private enum class Phase { CHOOSE, PICK, AUTH, WORK }
    private val work by viewModels<ThreadExportWork>()
    private lateinit var target: ConversationId
    private lateinit var trace: DebugTrace
    private lateinit var detail: TextView
    private lateinit var save: Button
    private lateinit var copy: Button
    private var phase = Phase.CHOOSE
    private var generation = -1L
    private var destination: Uri? = null
    private var ended = false

    private val picker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (phase != Phase.PICK) return@registerForActivityResult
        trace.step(DiagnosticStep.PICKER_RETURNED)
        if (uri == null) { cancel(); return@registerForActivityResult }
        if (uri.scheme != "content") { fail(); return@registerForActivityResult }
        destination = uri
        authorize()
    }

    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (phase != Phase.AUTH) return@registerForActivityResult
        if (result.resultCode == RESULT_OK) startExport(true) else cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val did = intent.getStringExtra("line")
        val peer = intent.getStringExtra("peer")
        if (did == null || peer == null) { finish(); return }
        target = ConversationId(did, peer)
        generation = intent.getLongExtra("generation", -1)
        trace = DebugJournal.resume(this, DiagnosticAction.THREAD_EXPORT,
            savedInstanceState?.getString("operation") ?: intent.getStringExtra("operation"))
        if (savedInstanceState != null) {
            phase = Phase.valueOf(savedInstanceState.getString("phase") ?: Phase.CHOOSE.name)
            destination = BundleCompat.getParcelable(savedInstanceState, "destination", Uri::class.java)
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        detail = TextView(this)
        save = Button(this).apply {
            setText(R.string.thread_export_save)
            setOnClickListener {
                phase = Phase.PICK
                controls()
                try {
                    picker.launch("sms-history.json")
                    trace.step(DiagnosticStep.PICKER_OPENED)
                } catch (e: Exception) { fail(e) }
            }
        }
        copy = Button(this).apply {
            setText(R.string.thread_export_copy)
            setOnClickListener { destination = null; authorize() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(detail); addView(save); addView(copy)
        })
        controls()
        if (phase == Phase.WORK && !work.started) {
            // A killed process no longer possesses the user's authorization or a live writer.
            trace.step(DiagnosticStep.CANCELLED)
            restartChoice(R.string.thread_export_interrupted)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                work.result.collect { result -> if (result != null && !isFinishing) complete(result) }
            }
        }
    }

    private fun controls() {
        val choosing = phase == Phase.CHOOSE
        val scope = getString(R.string.thread_export_scope)
        detail.text = when {
            !choosing -> getString(R.string.thread_export_busy)
            PrivateThreads.isPrivate(this, target) -> scope + "\n\n" + getString(R.string.thread_export_private)
            else -> scope
        }
        save.isEnabled = choosing
        copy.isEnabled = choosing
    }

    @Suppress("DEPRECATION")
    private fun authorize() {
        if (PrivateThreads.isPrivate(this, target)) {
            phase = Phase.AUTH
            controls()
            trace.step(DiagnosticStep.AUTHORIZING)
            try {
                val gate = getSystemService(KeyguardManager::class.java)
                    .createConfirmDeviceCredentialIntent(getString(R.string.private_threads), getString(R.string.thread_export_private))
                if (gate == null) fail() else credential.launch(gate)
            } catch (e: Exception) { fail(e) }
        } else startExport(false)
    }

    private fun startExport(privateAuthorized: Boolean) {
        phase = Phase.WORK
        controls()
        work.start(applicationContext, target, generation, privateAuthorized, destination, trace)
    }

    private fun complete(result: ThreadExportResult) {
        work.result.value = null
        when (result) {
            is ThreadExportResult.Clipboard -> {
                // Returning from another screen cannot deliver private clipboard content without an active authorization.
                if (!ThreadExportWork.permitted(this, target, generation, result.privateAuthorized) ||
                    getSystemService(KeyguardManager::class.java).isDeviceLocked) {
                    fail(SecurityException())
                    return
                }
                try {
                    val clip = ClipData.newPlainText(getString(R.string.conversation_conversation_clipboard_description), result.text)
                    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    trace.step(DiagnosticStep.COMPLETED, count = result.count)
                    done(R.string.conversation_copied_conversation_toast_message)
                } catch (e: Exception) {
                    trace.step(DiagnosticStep.FAILED, error = e)
                    restartChoice(R.string.conversation_copied_conversation_fail_toast_message)
                }
            }
            is ThreadExportResult.Saved -> done(if (result.verified) R.string.thread_export_verified else R.string.thread_export_written)
            is ThreadExportResult.Problem -> {
                if (result.message == R.string.thread_export_clip_limit) restartChoice(result.message) else done(result.message)
            }
        }
    }

    private fun restartChoice(message: Int) {
        work.reset()
        destination = null
        phase = Phase.CHOOSE
        trace = DebugJournal.begin(this, DiagnosticAction.THREAD_EXPORT)
        controls()
        detail.setText(message)
    }

    private fun done(message: Int) {
        ended = true
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun fail(error: Exception? = null) {
        trace.step(DiagnosticStep.FAILED, error = error)
        done(R.string.thread_export_error)
    }

    private fun cancel() {
        trace.step(DiagnosticStep.CANCELLED)
        ended = true
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (phase == Phase.WORK && destination == null && !isChangingConfigurations &&
            ::target.isInitialized && PrivateThreads.isPrivate(this, target)) {
            work.reset()
            cancel()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("phase", phase.name)
        outState.putParcelable("destination", destination)
        if (::trace.isInitialized) outState.putString("operation", trace.id)
    }

    override fun onDestroy() {
        if (isFinishing && !ended && ::trace.isInitialized && phase != Phase.WORK) trace.step(DiagnosticStep.CANCELLED)
        super.onDestroy()
    }

    companion object {
        fun open(owner: AppCompatActivity, target: ConversationId) {
            val trace = DebugJournal.begin(owner, DiagnosticAction.THREAD_EXPORT)
            try {
                owner.startActivity(Intent(owner, ThreadExportActivity::class.java)
                    .putExtra("line", target.did).putExtra("peer", target.contact)
                    .putExtra("generation", currentDirectoryAccountGeneration(owner)).putExtra("operation", trace.id))
            } catch (e: Exception) {
                trace.step(DiagnosticStep.FAILED, error = e)
                Toast.makeText(owner, R.string.thread_export_error, Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal sealed interface ThreadExportResult {
    data class Clipboard(val text: String, val count: Long, val privateAuthorized: Boolean) : ThreadExportResult
    data class Saved(val verified: Boolean) : ThreadExportResult
    data class Problem(val message: Int) : ThreadExportResult
}

internal class ThreadExportWork : ViewModel() {
    val result = MutableStateFlow<ThreadExportResult?>(null)
    private var job: Job? = null
    val started get() = job != null
    fun reset() { job?.cancel(); job = null; result.value = null }

    fun start(context: Context, target: ConversationId, generation: Long, privateAuthorized: Boolean, uri: Uri?, trace: DebugTrace) {
        if (started) return
        job = viewModelScope.launch(Dispatchers.IO) {
            val scope = currentCoroutineContext()
            fun checkPermission() {
                scope.ensureActive()
                if (!permitted(context, target, generation, privateAuthorized)) throw SecurityException()
            }
            suspend fun readPages(visit: (List<Message>) -> Unit) {
                trace.step(DiagnosticStep.QUERYING)
                if (BuildConfig.IS_DEMO) {
                    visit(getConversationDemoMessages(false).sortedWith(compareBy<Message> { it.date }.thenBy { it.databaseId }))
                } else {
                    withCurrentAccount(context, generation) {
                        Database.getInstance(context).readThreadExportPages(target, ::checkPermission, visit)
                    } ?: throw SecurityException()
                }
            }
            suspend fun transfer(): ThreadExportResult {
                trace.step(DiagnosticStep.VALIDATING)
                checkPermission()
                if (uri == null) {
                    val content = ThreadJsonExport.clipboard({ scope.ensureActive() }, ::readPages)
                    return ThreadExportResult.Clipboard(content.text, content.count, privateAuthorized)
                }
                // Unlink before writing any content. Linux reclaims the snapshot when this descriptor
                // closes, including process death. The external provider never holds a Room transaction.
                val temp = File.createTempFile("sms-export-", ".tmp", context.cacheDir)
                var linked = true
                try {
                    RandomAccessFile(temp, "rw").use { snapshot ->
                        if (!temp.delete()) throw IOException("Cannot unlink export staging file")
                        linked = false
                        val sink = Channels.newOutputStream(snapshot.channel).sink().buffer()
                        val count = ThreadJsonExport.write(sink, { scope.ensureActive() }, ::readPages)
                        trace.step(DiagnosticStep.SNAPSHOT, count = count)
                        checkPermission()
                        snapshot.seek(0)
                        trace.step(DiagnosticStep.OPENING_STREAM)
                        val verified = DocumentOutput.write(
                            openOutput = { mode -> context.contentResolver.openOutputStream(uri, mode) },
                            openInput = { context.contentResolver.openInputStream(uri) },
                            writeContent = { output ->
                                trace.step(DiagnosticStep.WRITING)
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    checkPermission()
                                    val n = snapshot.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                }
                            },
                            verifying = { trace.step(DiagnosticStep.VERIFYING) },
                            checkActive = ::checkPermission
                        )
                        trace.step(if (verified) DiagnosticStep.COMPLETED else DiagnosticStep.PARTIAL, count = count)
                        return ThreadExportResult.Saved(verified)
                    }
                } finally {
                    if (linked) temp.delete()
                }
            }
            try {
                result.value = transfer()
            } catch (e: CancellationException) {
                trace.step(DiagnosticStep.CANCELLED)
                throw e
            } catch (e: Exception) {
                trace.step(DiagnosticStep.FAILED, error = e)
                result.value = ThreadExportResult.Problem(when (e) {
                    is ThreadJsonExport.ClipboardLimit -> R.string.thread_export_clip_limit
                    is SecurityException -> R.string.thread_export_account_changed
                    else -> R.string.thread_export_error
                })
            }
        }
    }

    companion object {
        fun permitted(context: Context, target: ConversationId, generation: Long, privateAuthorized: Boolean): Boolean =
            (BuildConfig.IS_DEMO || (generation == currentDirectoryAccountGeneration(context) &&
                target.did in getDids(context, onlyShowInConversationsView = true))) &&
                (!PrivateThreads.isPrivate(context, target) ||
                    (privateAuthorized && !context.getSystemService(KeyguardManager::class.java).isDeviceLocked))
    }
}
