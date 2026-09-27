package kr.chanhee.eml2txt

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The only screen:
 *  1. choose an .eml (or open one from another app with "Open with" / "Share")
 *  2. it is converted by [EmlConverter] and previewed; for e-mails, checkboxes choose
 *     whether the header, the message text and text attachments go in
 *  3. "Save as .txt" asks Android where to save it (Downloads, Drive, …)
 *
 * The app has no internet permission: files never leave the phone.
 */
class MainActivity : Activity() {

    private lateinit var pickButton: Button
    private lateinit var saveButton: Button
    private lateinit var statusText: TextView
    private lateinit var previewText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var optionsGroup: View
    private lateinit var headerBox: CheckBox
    private lateinit var bodyBox: CheckBox
    private lateinit var attachmentsBox: CheckBox

    private var doc: EmlConverter.Document? = null
    private var inputName: String? = null
    private var busy = false
    private var hasOutput = false // the current options produce something to save

    /** Increases with every load, so a slow older load can't overwrite a newer one. */
    private var loadGeneration = 0

    /** True while the last file is being reloaded after Android recreated the screen. */
    private var restoring = false
    private var pendingSaveUri: Uri? = null

    private val prefs by lazy { getSharedPreferences("options", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pickButton = findViewById(R.id.pickButton)
        saveButton = findViewById(R.id.saveButton)
        statusText = findViewById(R.id.statusText)
        previewText = findViewById(R.id.previewText)
        progress = findViewById(R.id.progress)
        optionsGroup = findViewById(R.id.optionsGroup)
        headerBox = findViewById(R.id.headerBox)
        bodyBox = findViewById(R.id.bodyBox)
        attachmentsBox = findViewById(R.id.attachmentsBox)

        headerBox.isChecked = prefs.getBoolean(PREF_HEADER, true)
        bodyBox.isChecked = prefs.getBoolean(PREF_BODY, true)
        attachmentsBox.isChecked = prefs.getBoolean(PREF_ATTACHMENTS, true)
        for (box in listOf(headerBox, bodyBox, attachmentsBox)) {
            box.setOnCheckedChangeListener { _, _ -> rememberOptions(); refreshPreview() }
        }

        pickButton.setOnClickListener { pickFile() }
        saveButton.setOnClickListener { askWhereToSave() }
        updateButtons()

        if (savedInstanceState == null) handleIncomingIntent(intent) else restore(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** A file opened from a file manager / Gmail ("Open with"), or a file or text shared to this app. */
    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { loadUri(it) }
            Intent.ACTION_SEND -> {
                val stream: Uri? =
                    if (Build.VERSION.SDK_INT >= 34) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                val uri = stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                when {
                    uri != null -> loadUri(uri)
                    !text.isNullOrEmpty() -> loadText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
                }
            }
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // .eml files are labelled inconsistently, so show everything
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_OPEN)
    }

    private fun askWhereToSave() {
        val d = doc ?: return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, EmlConverter.suggestFileName(inputName, d))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_SAVE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_OPEN -> loadUri(uri)
            REQUEST_SAVE -> if (restoring) pendingSaveUri = uri else save(uri)
        }
    }

    // ------------------------------------------------------------------ reading

    private fun loadUri(uri: Uri) = load(displayName(uri)) {
        contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("cannot open $uri")
    }

    private fun loadText(text: String, name: String?) = load(name) { text.toByteArray(Charsets.UTF_8) }

    private fun load(name: String?, read: () -> ByteArray) {
        val generation = ++loadGeneration
        inputName = name
        doc = null
        hasOutput = false
        restoring = false
        previewText.text = ""
        optionsGroup.visibility = View.GONE
        setBusy(true)
        statusText.text = getString(R.string.status_reading, name ?: "…")

        thread(name = "eml-convert") {
            var parsed: EmlConverter.Document? = null
            var error: Throwable? = null
            try {
                val bytes = read()
                parsed = EmlConverter.parse(bytes)
                rememberInput(bytes)
            } catch (t: Throwable) {
                error = t
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                setBusy(false)
                if (parsed != null) show(parsed) else showError(error)
            }
        }
    }

    private fun show(d: EmlConverter.Document) {
        doc = d
        statusText.text = when (d) {
            is EmlConverter.PlainText -> {
                optionsGroup.visibility = View.GONE
                getString(R.string.status_plain, EmlConverter.lineCount(d.text))
            }
            is EmlConverter.Email -> {
                optionsGroup.visibility = View.VISIBLE
                val m = d.message
                buildString {
                    m.subject?.let { append(getString(R.string.status_subject, it)).append('\n') }
                    m.from?.let { append(getString(R.string.status_from, it)).append('\n') }
                    if (m.attachments.isNotEmpty()) {
                        append(getString(R.string.status_attachments, m.attachments.size, m.sections.size)).append('\n')
                    }
                    append(getString(R.string.status_email_ready))
                }
            }
        }
        refreshPreview()
    }

    private fun options() = EmlConverter.Options(headerBox.isChecked, bodyBox.isChecked, attachmentsBox.isChecked)

    private fun currentText(): String? = doc?.let { EmlConverter.render(it, options()) }

    private fun refreshPreview() {
        val text = currentText() ?: return
        hasOutput = text.isNotBlank()
        val lines = EmlConverter.lineCount(text)
        previewText.text = when {
            text.isBlank() -> getString(R.string.preview_empty)
            lines > PREVIEW_LINES ->
                text.lineSequence().take(PREVIEW_LINES).joinToString("\n") + "\n\n" +
                    getString(R.string.preview_truncated, PREVIEW_LINES, lines)
            else -> text
        }
        updateButtons()
    }

    private fun showError(error: Throwable?) {
        statusText.text = when ((error as? EmlConverter.ConversionException)?.reason) {
            EmlConverter.Reason.EMPTY_FILE -> getString(R.string.error_empty)
            EmlConverter.Reason.NOT_TEXT -> getString(R.string.error_not_text)
            null -> when (error) {
                is OutOfMemoryError -> getString(R.string.error_too_big)
                else -> getString(R.string.error_generic, error?.message ?: error?.javaClass?.simpleName ?: "?")
            }
        }
    }

    // ------------------------------------------------------------------ saving

    private fun save(uri: Uri) {
        val text = currentText()
        if (text.isNullOrBlank()) {
            // Nothing to write (e.g. Android closed the app while the save dialog was open).
            discardEmptyDocument(uri)
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true)
        thread(name = "txt-save") {
            val ok = try {
                val out = contentResolver.openOutputStream(uri, "w") ?: throw IOException("no output stream")
                out.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                true
            } catch (t: Throwable) {
                false
            }
            if (!ok) discardEmptyDocument(uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                val message = if (ok) getString(R.string.saved, displayName(uri) ?: "") else getString(R.string.save_failed)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun discardEmptyDocument(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (e: Exception) {
            // not deletable here; leave it
        }
    }

    // ------------------------------------------------------------------ surviving screen recreation

    // Android may recreate this screen (dark-mode switch) or close the app while the file
    // dialogs are open. The last input is kept in the app's private cache so it can come back.

    private fun cacheFile() = File(cacheDir, "last_input")

    private fun rememberInput(bytes: ByteArray) {
        try {
            cacheFile().writeBytes(bytes)
        } catch (t: Throwable) {
            // only a convenience
        }
    }

    private fun rememberOptions() {
        prefs.edit()
            .putBoolean(PREF_HEADER, headerBox.isChecked)
            .putBoolean(PREF_BODY, bodyBox.isChecked)
            .putBoolean(PREF_ATTACHMENTS, attachmentsBox.isChecked)
            .apply()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_INPUT_NAME, inputName)
        outState.putCharSequence(KEY_STATUS, statusText.text)
        outState.putBoolean(KEY_HAS_DOC, doc != null)
    }

    private fun restore(state: Bundle) {
        inputName = state.getString(KEY_INPUT_NAME)
        state.getCharSequence(KEY_STATUS)?.let { statusText.text = it }
        if (!state.getBoolean(KEY_HAS_DOC)) return

        val generation = ++loadGeneration
        restoring = true
        setBusy(true)
        thread(name = "restore") {
            val parsed = try {
                EmlConverter.parse(cacheFile().readBytes())
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                restoring = false
                setBusy(false)
                parsed?.let { show(it) }
                pendingSaveUri?.let { pendingSaveUri = null; save(it) }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        updateButtons()
    }

    private fun updateButtons() {
        pickButton.isEnabled = !busy
        for (box in listOf(headerBox, bodyBox, attachmentsBox)) box.isEnabled = !busy
        saveButton.isEnabled = !busy && hasOutput
    }

    private fun displayName(uri: Uri): String? {
        val fromProvider = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return fromProvider ?: uri.lastPathSegment
    }

    private companion object {
        const val REQUEST_OPEN = 1
        const val REQUEST_SAVE = 2
        const val PREVIEW_LINES = 300
        const val KEY_INPUT_NAME = "inputName"
        const val KEY_STATUS = "status"
        const val KEY_HAS_DOC = "hasDoc"
        const val PREF_HEADER = "header"
        const val PREF_BODY = "body"
        const val PREF_ATTACHMENTS = "attachments"
    }
}
