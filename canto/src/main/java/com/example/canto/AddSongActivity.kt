package com.example.canto

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File

class AddSongActivity : Activity() {

    private var instrumentalUri: Uri? = null
    private var vocalUri: Uri? = null

    private lateinit var titleEdit: EditText
    private lateinit var instrumentalButton: Button
    private lateinit var vocalButton: Button
    private lateinit var saveButton: Button
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#16263A"))
            setPadding(dp(20), dp(24), dp(20), dp(24))
            fitsSystemWindows = true
        }
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        root.addView(TextView(this).apply {
            text = "Aggiungi canzone"
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Servono due file audio della stessa canzone: la base strumentale e la sola voce originale. Devono avere la stessa durata e partire insieme."
            textSize = 14f
            setTextColor(Color.parseColor("#A9BCCB"))
        }, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(8) })

        titleEdit = EditText(this).apply {
            hint = "Titolo della canzone"
            setHintTextColor(Color.parseColor("#7A8A99"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
        }
        root.addView(titleEdit, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(16) })

        instrumentalButton = Button(this).apply {
            text = "Scegli la base strumentale"
            setOnClickListener { pick(REQ_INSTRUMENTAL) }
        }
        root.addView(instrumentalButton, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(12) })

        vocalButton = Button(this).apply {
            text = "Scegli la voce originale"
            setOnClickListener { pick(REQ_VOCAL) }
        }
        root.addView(vocalButton, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(8) })

        saveButton = Button(this).apply {
            text = "Analizza e salva"
            setOnClickListener { startImport() }
        }
        root.addView(saveButton, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(24) })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = android.view.View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(16) })

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#A9BCCB"))
        }
        root.addView(statusText, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(8) })

        setContentView(root)
    }

    private fun pick(code: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val name = displayName(uri)
        if (requestCode == REQ_INSTRUMENTAL) {
            instrumentalUri = uri
            instrumentalButton.text = "Base: $name"
        } else if (requestCode == REQ_VOCAL) {
            vocalUri = uri
            vocalButton.text = "Voce: $name"
        }
        if (titleEdit.text.isNullOrBlank()) {
            titleEdit.setText(name.substringBeforeLast('.'))
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
            }
        } catch (e: Exception) {
        }
        return "file audio"
    }

    private fun setBusy(busy: Boolean) {
        instrumentalButton.isEnabled = !busy
        vocalButton.isEnabled = !busy
        saveButton.isEnabled = !busy
        titleEdit.isEnabled = !busy
        progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun startImport() {
        val instr = instrumentalUri
        val vocal = vocalUri
        if (instr == null || vocal == null) {
            statusText.text = "Scegli entrambi i file audio."
            return
        }
        val title = titleEdit.text.toString().trim().ifEmpty { "Canzone senza titolo" }
        setBusy(true)
        progress.progress = 0
        statusText.text = "Copia dei file..."

        val store = SongStore(this)
        Thread {
            val id = System.currentTimeMillis().toString()
            val dir = store.songDir(id)
            try {
                dir.mkdirs()
                val instrFile = File(dir, "instrumental")
                val vocalFile = File(dir, "vocal")
                copy(instr, instrFile)
                copy(vocal, vocalFile)

                runOnUiThread { statusText.text = "Lettura della voce originale..." }
                val pcm = AudioDecoder.decodeMono(vocalFile.path, 22050) { p ->
                    runOnUiThread { progress.progress = (p * 400).toInt() }
                }

                runOnUiThread { statusText.text = "Analisi delle note (può richiedere qualche secondo)..." }
                val track = PitchAnalyzer.analyze(pcm) { p ->
                    runOnUiThread { progress.progress = 400 + (p * 600).toInt() }
                }

                val voiced = track.midi.count { !it.isNaN() }
                if (voiced < 20) throw IllegalStateException("Nella traccia vocale non sono state trovate note chiare")

                val pitchFile = File(dir, "pitch.bin")
                track.save(pitchFile)

                var durationMs = 0
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(instrFile.path)
                    durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                } finally {
                    mmr.release()
                }
                if (durationMs <= 0) durationMs = (track.midi.size * track.hopSec * 1000).toInt()

                store.add(
                    Song(id, title, instrFile.path, vocalFile.path, pitchFile.path, durationMs)
                )
                runOnUiThread { finish() }
            } catch (e: Exception) {
                dir.deleteRecursively()
                runOnUiThread {
                    statusText.text = "Errore: " + (e.message ?: e.javaClass.simpleName)
                    setBusy(false)
                }
            }
        }.start()
    }

    private fun copy(uri: Uri, target: File) {
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("Impossibile aprire il file")
        input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
    }

    companion object {
        private const val REQ_INSTRUMENTAL = 1
        private const val REQ_VOCAL = 2
    }
}
