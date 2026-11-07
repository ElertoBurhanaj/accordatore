package com.example.cantonuovo

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var library: SongLibrary
    private lateinit var songList: LinearLayout
    private lateinit var titleInput: EditText
    private lateinit var baseButton: android.widget.Button
    private lateinit var vocalButton: android.widget.Button
    private lateinit var addButton: android.widget.Button
    private lateinit var importStatus: TextView
    private var baseUri: Uri? = null
    private var vocalUri: Uri? = null
    private var busy = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AppUi.BACKGROUND
        window.navigationBarColor = AppUi.BACKGROUND
        library = SongLibrary(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppUi.BACKGROUND)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AppUi.dp(this@MainActivity, 20), AppUi.dp(this@MainActivity, 24),
                AppUi.dp(this@MainActivity, 20), AppUi.dp(this@MainActivity, 24))
        }
        scroll.addView(column)
        content.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        column.addView(AppUi.text(this, "INTONA", 27f, AppUi.TEXT, bold = true))
        column.addView(AppUi.text(
            this,
            "Canta sulla base e confronta la tua voce con le note originali.",
            15f,
            AppUi.MUTED
        ), topMargin(6))

        val importPanel = AppUi.panel(this)
        importPanel.addView(AppUi.text(this, "Aggiungi un brano", 19f, bold = true))
        importPanel.addView(AppUi.text(
            this,
            "Seleziona la base strumentale e una traccia con la voce solista. I due file devono partire dallo stesso punto; l’analisi funziona meglio con la voce separata dalla base.",
            14f,
            AppUi.MUTED
        ), topMargin(5))
        titleInput = EditText(this).apply {
            hint = "Titolo del brano"
            setHintTextColor(AppUi.MUTED)
            setTextColor(AppUi.TEXT)
            maxLines = 1
            setSingleLine(true)
        }
        importPanel.addView(titleInput, topMargin(8))
        baseButton = AppUi.button(this, "1  ·  Scegli la base") {
            launchPicker(SELECT_BASE)
        }
        importPanel.addView(baseButton, topMargin(5))
        vocalButton = AppUi.button(this, "2  ·  Scegli la voce originale") {
            launchPicker(SELECT_VOCAL)
        }
        importPanel.addView(vocalButton, topMargin(2))
        addButton = AppUi.button(this, "Analizza e aggiungi") { importSong() }
        importPanel.addView(addButton, topMargin(5))
        importStatus = AppUi.text(this, "", 13f, AppUi.MUTED)
        importPanel.addView(importStatus, topMargin(4))
        column.addView(importPanel, topMargin(20))

        column.addView(AppUi.text(this, "La tua libreria", 20f, bold = true), topMargin(22))
        songList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(songList, topMargin(8))
        setContentView(content)
        refreshLibrary()
    }

    override fun onResume() {
        super.onResume()
        if (::songList.isInitialized) refreshLibrary()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            SELECT_BASE -> {
                baseUri = uri
                baseButton.text = "✓  Base: ${displayName(uri)}"
            }
            SELECT_VOCAL -> {
                vocalUri = uri
                vocalButton.text = "✓  Voce: ${displayName(uri)}"
            }
        }
    }

    private fun launchPicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCode)
    }

    private fun importSong() {
        if (busy) return
        val instrumental = baseUri
        val originalVoice = vocalUri
        if (instrumental == null || originalVoice == null) {
            importStatus.text = "Seleziona entrambi i file audio prima di continuare."
            return
        }

        busy = true
        setImportEnabled(false)
        importStatus.text = "Copia e analisi in corso…"
        val title = titleInput.text.toString().trim().ifBlank {
            displayName(instrumental).substringBeforeLast('.').ifBlank { "Nuovo brano" }
        }
        val id = library.newSongId()
        val folder = library.songFolder(id)

        Thread({
            try {
                check(folder.mkdirs()) { "Impossibile creare la cartella del brano" }
                val baseFile = File(folder, "base.audio")
                val vocalFile = File(folder, "voce.audio")
                val trackFile = File(folder, "note.bin")
                copyFromUri(instrumental, baseFile)
                copyFromUri(originalVoice, vocalFile)

                val baseDuration = readDuration(baseFile)
                require(baseDuration > 0) { "Impossibile leggere la durata della base" }

                val pcm = AudioDecoder.decode(vocalFile) { progress ->
                    updateImportStatus("Lettura della voce: ${(progress * 100).toInt()}%")
                }
                val track = VocalAnalyzer.analyze(pcm) { progress ->
                    updateImportStatus("Rilevamento delle note: ${(progress * 100).toInt()}%")
                }
                require(track.midi.count { it.isFinite() } >= 20) {
                    "Non ho trovato note cantate chiare. Scegli una traccia con la voce più evidente."
                }
                track.save(trackFile)
                library.add(Song(
                    id, title, baseFile.path, vocalFile.path, trackFile.path, baseDuration
                ))
                mainHandler.post {
                    baseUri = null
                    vocalUri = null
                    baseButton.text = "1  ·  Scegli la base"
                    vocalButton.text = "2  ·  Scegli la voce originale"
                    titleInput.text.clear()
                    importStatus.text = "Brano aggiunto. Collega le cuffie prima di esercitarti."
                    busy = false
                    setImportEnabled(true)
                    refreshLibrary()
                }
            } catch (error: Exception) {
                folder.deleteRecursively()
                mainHandler.post {
                    importStatus.text = "Errore: ${error.message ?: error.javaClass.simpleName}"
                    busy = false
                    setImportEnabled(true)
                }
            }
        }, "IntonaImport").start()
    }

    private fun copyFromUri(uri: Uri, destination: File) {
        val input = contentResolver.openInputStream(uri)
            ?: error("Impossibile aprire il file selezionato")
        input.use { source ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_AUDIO_FILE_BYTES) { "Ogni file audio deve essere inferiore a 250 MB" }
                    output.write(buffer, 0, count)
                }
                require(total > 0L) { "Il file audio è vuoto" }
            }
        }
    }

    private fun readDuration(file: File): Int {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            return retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toIntOrNull() ?: 0
        } finally {
            retriever.release()
        }
    }

    private fun updateImportStatus(message: String) {
        mainHandler.post {
            if (busy && !isFinishing) importStatus.text = message
        }
    }

    private fun setImportEnabled(enabled: Boolean) {
        baseButton.isEnabled = enabled
        vocalButton.isEnabled = enabled
        addButton.isEnabled = enabled
        titleInput.isEnabled = enabled
    }

    private fun refreshLibrary() {
        songList.removeAllViews()
        val songs = try {
            library.load()
        } catch (error: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Impossibile aprire la libreria")
                .setMessage(error.message ?: "Dati della libreria non validi")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (songs.isEmpty()) {
            songList.addView(AppUi.text(
                this,
                "I brani aggiunti appariranno qui. Ti servono una base e una traccia della voce originale.",
                14f,
                AppUi.MUTED
            ), topMargin(8))
            return
        }

        songs.forEach { song ->
            val row = AppUi.panel(this)
            row.addView(AppUi.text(this, song.title, 17f, bold = true))
            row.addView(AppUi.text(
                this,
                "${AppUi.formatTime(song.durationMs)}  ·  Tocca per cantare · tieni premuto per eliminare",
                12f,
                AppUi.MUTED
            ), topMargin(5))
            row.isClickable = true
            row.setOnClickListener {
                startActivity(Intent(this, PracticeActivity::class.java).putExtra(EXTRA_SONG_ID, song.id))
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Eliminare \"${song.title}\"?")
                    .setMessage("Saranno eliminati anche i file audio salvati sul dispositivo.")
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Elimina") { _, _ ->
                        try {
                            library.delete(song)
                            refreshLibrary()
                        } catch (error: Exception) {
                            Toast.makeText(this, error.message ?: "Impossibile eliminare il brano", Toast.LENGTH_LONG).show()
                        }
                    }
                    .show()
                true
            }
            songList.addView(row, topMargin(8))
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return "file audio"
    }

    private fun topMargin(valueDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = AppUi.dp(this@MainActivity, valueDp) }

    companion object {
        const val EXTRA_SONG_ID = "com.example.cantonuovo.SONG_ID"
        private const val SELECT_BASE = 10
        private const val SELECT_VOCAL = 11
        private const val MAX_AUDIO_FILE_BYTES = 250L * 1024L * 1024L
    }
}
