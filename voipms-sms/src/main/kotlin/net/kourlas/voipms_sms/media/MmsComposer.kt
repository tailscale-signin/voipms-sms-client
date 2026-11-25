package net.kourlas.voipms_sms.media

import android.net.Uri
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kourlas.voipms_sms.R

internal class MmsComposer(private val owner: AppCompatActivity, val draftKey: String, private val pickMedia: () -> Unit) {
    private val prefs = owner.getSharedPreferences("mms_drafts", 0)
    private val refs = AttachmentList.decode(prefs.getString(draftKey, "").orEmpty()).toMutableList()
    private val button = owner.findViewById<Button>(R.id.attach_mms)
    val attachments: List<String> get() = refs.toList()

    private var importing = false

    init {
        button.setOnClickListener {
            if (refs.isEmpty()) pick() else manage()
        }
        render()
    }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty() || importing) return
        if (uris.size + refs.size > MmsMedia.MAX_FILES) { notify(owner.getString(R.string.mms_max)); return }
        importing = true
        render()
        owner.lifecycleScope.launch {
            val staged = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) { uris.forEach { staged.add(MmsMedia.import(owner, it)) } }
                refs.addAll(staged)
                persist()
                manage()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notify(e.message ?: owner.getString(R.string.mms_unavailable)) }
            finally {
                staged.filterNot(refs::contains).forEach { MmsMedia.localFile(owner, it).delete() }
                importing = false
                render()
            }
        }
    }

    fun validate(text: String): Boolean {
        if (importing) { notify(owner.getString(R.string.mms_preparing)); return false }
        if (refs.isNotEmpty() && text.codePointCount(0, text.length) > MmsMedia.MAX_CAPTION) {
            notify(owner.getString(R.string.mms_caption_limit)); return false
        }
        return true
    }
    fun restore(attachments: List<String>) {
        if (refs.isEmpty() && attachments.size <= MmsMedia.MAX_FILES &&
            attachments.all { MmsMedia.isLocal(it) && MmsMedia.localFile(owner, it).isFile }) {
            refs.addAll(attachments)
            persist()
        }
    }
    fun clear() { refs.clear(); persist() }
    private fun persist() { prefs.edit().putString(draftKey, AttachmentList.encode(refs)).apply(); render() }
    private fun notify(message: String) = Toast.makeText(owner, message, Toast.LENGTH_LONG).show()
    private fun render() {
        button.text = if (refs.isEmpty()) owner.getString(R.string.mms_attach_symbol) else owner.getString(R.string.mms_attach_number, refs.size)
        button.contentDescription = owner.getString(R.string.mms_attach_description, refs.size)
        button.isEnabled = !importing
    }
    private fun pick() = pickMedia()
    private fun manage() {
        if (owner.isFinishing || owner.isDestroyed) return
        val labels = refs.mapIndexed { index, ref ->
            val file = MmsMedia.localFile(owner, ref)
            owner.getString(R.string.mms_file_label, index + 1, file.extension.uppercase(), (file.length() + 999) / 1000)
        }.toTypedArray()
        val dialog = AlertDialog.Builder(owner).setTitle(R.string.mms_attachments)
            .setItems(labels) { _, i -> options(refs[i]) }
            .setNegativeButton(android.R.string.ok, null)
        if (refs.size < MmsMedia.MAX_FILES) dialog.setPositiveButton(R.string.mms_add) { _, _ -> pick() }
        dialog.show()
    }
    private fun options(ref: String) {
        val file = MmsMedia.localFile(owner, ref)
        val edit = file.extension == "jpg" || file.extension == "png"
        val options = buildList {
            add(owner.getString(R.string.mms_preview))
            if (edit) add(owner.getString(R.string.mms_edit))
            add(owner.getString(R.string.mms_remove))
        }
        AlertDialog.Builder(owner).setItems(options.toTypedArray()) { _, which ->
            when {
                which == 0 -> MmsGallery.show(owner, 0L, refs.toList(), refs.indexOf(ref))
                edit && which == 1 -> PhotoEditor.open(owner, file) { replacement ->
                    val index = refs.indexOf(ref)
                    if (index >= 0) { refs[index] = replacement; file.delete(); persist(); manage() }
                }
                else -> { refs.remove(ref); file.delete(); persist() }
            }
        }.show()
    }
}
