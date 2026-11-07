package com.example.cantonuovo

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("SetTextI18n")
class PracticeActivity : Activity() {
    private lateinit var song: Song
    private lateinit var track: VocalTrack
    private lateinit var player: MediaPlayer
    private var referencePlayer: MediaPlayer? = null
    private lateinit var graph: VocalGraphView
    private lateinit var playButton: android.widget.Button
    private lateinit var timeLabel: TextView
    private lateinit var originalLabel: TextView
    private lateinit var userLabel: TextView
    private lateinit var feedbackLabel: TextView
    private lateinit var scoreLabel: TextView
    private lateinit var headphoneLabel: TextView
    private var ready = false
    private var referenceEnabled = false
    private var ignoreOctave = false
    private var referenceOffsetMs = 0
    private var microphoneLatencyMs = 80
    private var totalScoredFrames = 0
    private var accurateFrames = 0

    private val uiHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            pausePlayback()
        }
    }

    @Volatile private var microphoneRunning = false
    private var recorder: AudioRecord? = null
    private var microphoneThread: Thread? = null
    private val pitchStabilizer = PitchStabilizer()

    private val refreshTask = object : Runnable {
        override fun run() {
            if (!ready || isFinishing) return
            refreshPlaybackPosition()
            uiHandler.postDelayed(this, 40L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = AppUi.BACKGROUND
        window.navigationBarColor = AppUi.BACKGROUND

        try {
            val id = intent.getStringExtra(MainActivity.EXTRA_SONG_ID)
                ?: error("Brano non specificato")
            song = SongLibrary(this).load().firstOrNull { it.id == id }
                ?: error("Brano non trovato nella libreria")
            track = VocalTrack.load(File(song.trackPath)).cleaned()
            player = MediaPlayer().apply {
                setDataSource(song.basePath)
                prepare()
                setOnCompletionListener {
                    pausePlayback()
                    player.seekTo(0)
                    graph.setPosition(0)
                }
            }
        } catch (error: Exception) {
            Toast.makeText(this, "Impossibile aprire il brano: ${error.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildInterface()
        graph.setTrack(track)
        graph.setPosition(0)
        ready = true
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        uiHandler.removeCallbacks(refreshTask)
        uiHandler.post(refreshTask)
        updateHeadphoneStatus()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshTask)
        if (ready) pausePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        if (ready) {
            releaseFocus()
            player.release()
            referencePlayer?.release()
            referencePlayer = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startPlayback()
        } else if (requestCode == REQUEST_MICROPHONE) {
            feedbackLabel.text = "Per confrontare la tua voce, consenti l'accesso al microfono."
        }
    }

    private fun buildInterface() {
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(AppUi.BACKGROUND)
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AppUi.dp(this@PracticeActivity, 18), AppUi.dp(this@PracticeActivity, 12),
                AppUi.dp(this@PracticeActivity, 18), AppUi.dp(this@PracticeActivity, 22))
        }
        column.addView(AppUi.text(this, song.title, 22f, bold = true))
        column.addView(AppUi.text(
            this,
            "La linea blu è la voce originale; la linea verde o arancione è la tua.",
            13f,
            AppUi.MUTED
        ), margin(3))

        headphoneLabel = AppUi.text(this, "", 13f, AppUi.WARN, centered = true)
        column.addView(headphoneLabel, margin(6))

        graph = VocalGraphView(this)
        column.addView(graph, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, AppUi.dp(this, 310)
        ).apply { topMargin = AppUi.dp(this@PracticeActivity, 8) })

        val legend = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        legend.addView(AppUi.text(this, "━━ Originale", 13f, 0xFF63A8F5.toInt()))
        legend.addView(AppUi.text(this, "    ━━ Tu", 13f, AppUi.ACCENT))
        column.addView(legend, margin(2))

        val pitchCard = AppUi.panel(this).apply {
            gravity = Gravity.CENTER
        }
        originalLabel = AppUi.text(this, "ORIGINALE  ·  –", 15f, AppUi.MUTED, bold = true, centered = true)
        userLabel = AppUi.text(this, "LA TUA VOCE  ·  –", 22f, AppUi.TEXT, bold = true, centered = true)
        feedbackLabel = AppUi.text(this, "Premi Avvia e canta", 18f, AppUi.ACCENT, bold = true, centered = true)
        pitchCard.addView(originalLabel)
        pitchCard.addView(userLabel, margin(4))
        pitchCard.addView(feedbackLabel, margin(3))
        column.addView(pitchCard, margin(8))

        val positionRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val positionBar = SeekBar(this).apply {
            max = song.durationMs.coerceAtLeast(1)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeLabel.text =
                        "${AppUi.formatTime(progress)} / ${AppUi.formatTime(song.durationMs)}"
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    seekTo(bar.progress)
                }
            })
        }
        positionRow.addView(positionBar, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeLabel = AppUi.text(this,
            "0:00 / ${AppUi.formatTime(song.durationMs)}", 12f, AppUi.MUTED)
        positionRow.addView(timeLabel)
        column.addView(positionRow, margin(4))

        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        playButton = AppUi.button(this, "▶  Avvia") { onPlayPressed() }
        buttons.addView(playButton, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(AppUi.button(this, "↺  Da capo") {
            seekTo(0)
            resetScore()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(buttons, margin(2))

        column.addView(AppUi.text(this, "Regolazioni", 18f, bold = true), margin(10))

        val offsetValue = AppUi.text(this, "Sincronizzazione voce: 0 ms", 14f, AppUi.MUTED)
        column.addView(offsetValue, margin(4))
        column.addView(SeekBar(this).apply {
            max = REFERENCE_OFFSET_RANGE_MS * 2
            progress = REFERENCE_OFFSET_RANGE_MS
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    referenceOffsetMs = progress - REFERENCE_OFFSET_RANGE_MS
                    offsetValue.text = "Sincronizzazione voce: ${referenceOffsetMs} ms"
                    graph.setReferenceOffset(referenceOffsetMs)
                    syncReferencePlayer()
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        column.addView(AppUi.text(
            this,
            "Sposta l'originale se la voce e la base non partono insieme.",
            12f,
            AppUi.MUTED
        ), margin(0))

        val latencyValue = AppUi.text(this, "Ritardo del microfono: ${microphoneLatencyMs} ms",
            14f, AppUi.MUTED)
        column.addView(latencyValue, margin(8))
        column.addView(SeekBar(this).apply {
            max = MAX_MIC_LATENCY_MS
            progress = microphoneLatencyMs
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    microphoneLatencyMs = progress
                    latencyValue.text = "Ritardo del microfono: ${microphoneLatencyMs} ms"
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })

        column.addView(Switch(this).apply {
            text = "Confronta anche in altre ottave"
            setTextColor(AppUi.TEXT)
            setOnCheckedChangeListener { _, checked -> ignoreOctave = checked }
        }, margin(6))
        column.addView(Switch(this).apply {
            text = "Ascolta anche la voce originale"
            setTextColor(AppUi.TEXT)
            setOnCheckedChangeListener { _, checked -> setReferenceEnabled(checked) }
        }, margin(2))
        column.addView(AppUi.text(
            this,
            "Usa le cuffie: così il microfono sente la tua voce e non la base riprodotta.",
            12f,
            AppUi.MUTED
        ), margin(5))
        scoreLabel = AppUi.text(this, "Precisione durante il brano: –", 14f, AppUi.MUTED, centered = true)
        column.addView(scoreLabel, margin(10))
        scroll.addView(column)
        setContentView(scroll)
    }

    private fun onPlayPressed() {
        if (player.isPlaying) {
            pausePlayback()
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (player.currentPosition >= player.duration - 150) seekTo(0)
        if (!requestFocus()) {
            feedbackLabel.text = "Un'altra app sta usando l'audio. Riprova tra poco."
            return
        }
        player.start()
        playButton.text = "⏸  Pausa"
        graph.setPosition(player.currentPosition)
        syncReferencePlayer()
        startMicrophone()
    }

    private fun pausePlayback() {
        if (::player.isInitialized && player.isPlaying) player.pause()
        referencePlayer?.pause()
        playButton.text = "▶  Avvia"
        if (::graph.isInitialized && ::player.isInitialized) graph.setPosition(player.currentPosition)
        stopMicrophone()
        releaseFocus()
    }

    private fun seekTo(positionMs: Int) {
        val position = positionMs.coerceIn(0, player.duration)
        player.seekTo(position)
        referencePlayer?.let { reference ->
            reference.seekTo((position - referenceOffsetMs).coerceIn(0, reference.duration))
        }
        graph.setPosition(position)
        pitchStabilizer.reset()
        userLabel.text = "LA TUA VOCE  ·  –"
        feedbackLabel.text = "Canta quando riparte la musica"
    }

    private fun refreshPlaybackPosition() {
        if (!player.isPlaying) return
        val position = player.currentPosition.coerceIn(0, player.duration)
        graph.setPosition(position)
        timeLabel.text =
            "${AppUi.formatTime(position)} / ${AppUi.formatTime(song.durationMs)}"
        syncReferencePlayer()
    }

    private fun setReferenceEnabled(enabled: Boolean) {
        referenceEnabled = enabled
        if (!enabled) {
            referencePlayer?.release()
            referencePlayer = null
            return
        }
        try {
            referencePlayer = MediaPlayer().apply {
                setDataSource(song.vocalPath)
                prepare()
            }
            syncReferencePlayer()
        } catch (error: Exception) {
            referencePlayer?.release()
            referencePlayer = null
            referenceEnabled = false
            Toast.makeText(
                this,
                "Impossibile riprodurre la voce originale: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun syncReferencePlayer() {
        val reference = referencePlayer ?: return
        val basePosition = player.currentPosition
        val referencePosition = (basePosition - referenceOffsetMs).coerceIn(0, reference.duration)
        if (abs(reference.currentPosition - referencePosition) > SYNC_TOLERANCE_MS) {
            reference.seekTo(referencePosition)
        }
        if (player.isPlaying && basePosition >= referenceOffsetMs.coerceAtLeast(0)) {
            if (!reference.isPlaying) reference.start()
        } else if (reference.isPlaying) {
            reference.pause()
        }
    }

    private fun resetScore() {
        totalScoredFrames = 0
        accurateFrames = 0
        scoreLabel.text = "Precisione durante il brano: –"
        graph.clearUserNotes()
    }

    private fun startMicrophone() {
        if (microphoneRunning) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            feedbackLabel.text = "Per confrontare la tua voce, consenti l'accesso al microfono."
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
            return
        }
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBuffer <= 0) {
            feedbackLabel.text = "Questo dispositivo non supporta la registrazione audio richiesta."
            return
        }
        val newRecorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBuffer, FRAME_SIZE * 4)
            )
        } catch (error: Exception) {
            feedbackLabel.text = "Microfono non disponibile: ${error.message}"
            return
        }
        if (newRecorder.state != AudioRecord.STATE_INITIALIZED) {
            newRecorder.release()
            feedbackLabel.text = "Impossibile inizializzare il microfono."
            return
        }

        recorder = newRecorder
        microphoneRunning = true
        pitchStabilizer.reset()
        val thread = Thread({
            val window = FloatArray(FRAME_SIZE)
            val incoming = ShortArray(HOP_SIZE)
            val detector = YinPitchDetector(SAMPLE_RATE, FRAME_SIZE)
            try {
                newRecorder.startRecording()
                while (microphoneRunning) {
                    var filled = 0
                    while (filled < incoming.size && microphoneRunning) {
                        val read = newRecorder.read(incoming, filled, incoming.size - filled)
                        if (read < 0) {
                            if (microphoneRunning) runOnUiThread {
                                feedbackLabel.text = "Lettura del microfono interrotta ($read)."
                            }
                            microphoneRunning = false
                            break
                        }
                        if (read == 0) break
                        filled += read
                    }
                    if (!microphoneRunning) break
                    if (filled != incoming.size) {
                        Thread.sleep(5)
                        continue
                    }

                    System.arraycopy(window, HOP_SIZE, window, 0, FRAME_SIZE - HOP_SIZE)
                    for (index in incoming.indices) {
                        window[FRAME_SIZE - HOP_SIZE + index] = incoming[index] / 32768f
                    }
                    val raw = Notes.midi(detector.detect(window))
                    val stable = pitchStabilizer.update(raw)
                    runOnUiThread {
                        if (microphoneRunning) showPitch(stable)
                    }
                }
            } catch (error: Exception) {
                if (microphoneRunning) runOnUiThread {
                    feedbackLabel.text = "Errore del microfono: ${error.message ?: error.javaClass.simpleName}"
                }
            } finally {
                microphoneRunning = false
                try {
                    newRecorder.stop()
                } catch (_: IllegalStateException) {
                    // The recorder can already be stopped by the activity.
                }
                newRecorder.release()
                if (recorder === newRecorder) recorder = null
            }
        }, "IntonaMicrophone")
        microphoneThread = thread
        thread.start()
    }

    private fun stopMicrophone() {
        microphoneRunning = false
        recorder?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // The recording thread may have already stopped it.
            }
        }
        microphoneThread?.interrupt()
        microphoneThread = null
        pitchStabilizer.reset()
    }

    private fun showPitch(userMidi: Float) {
        if (!userMidi.isFinite()) {
            userLabel.text = "LA TUA VOCE  ·  –"
            feedbackLabel.text = "Canta una nota tenuta"
            return
        }

        val baseTimeMs = player.currentPosition - microphoneLatencyMs
        val reference = track.nearestVoiced(
            (baseTimeMs - referenceOffsetMs) / 1000f,
            radius = 3
        )
        userLabel.text = "LA TUA VOCE  ·  ${Notes.name(userMidi)}"
        val centsFromUserNote = Notes.cents(userMidi)
        userLabel.append("  ${if (centsFromUserNote >= 0) "+" else ""}${centsFromUserNote}¢")

        if (!reference.isFinite()) {
            originalLabel.text = "ORIGINALE  ·  –"
            feedbackLabel.text = "In questo punto non c'è una nota originale"
            feedbackLabel.setTextColor(AppUi.MUTED)
            return
        }
        originalLabel.text = "ORIGINALE  ·  ${Notes.name(reference)}"

        var difference = userMidi - reference
        if (ignoreOctave) difference -= (difference / 12f).roundToInt() * 12f
        val centsDifference = (difference * 100).roundToInt()
        val accurate = abs(difference) <= NOTE_TOLERANCE_SEMITONES
        if (player.isPlaying && baseTimeMs >= 0) {
            graph.addUserNote(baseTimeMs, userMidi, accurate)
            totalScoredFrames++
            if (accurate) accurateFrames++
            scoreLabel.text =
                "Precisione durante il brano: ${accurateFrames * 100 / totalScoredFrames}%"
        }

        feedbackLabel.text = when {
            accurate -> "Intonazione corretta ✓"
            centsDifference > 0 -> "Abbassa di ${centsDifference}¢"
            else -> "Alza di ${abs(centsDifference)}¢"
        }
        feedbackLabel.setTextColor(if (accurate) AppUi.ACCENT else AppUi.WARN)
    }

    private fun updateHeadphoneStatus() {
        val connected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        headphoneLabel.text = if (connected) "Cuffie collegate" else "Collega le cuffie per un confronto preciso"
        headphoneLabel.setTextColor(if (connected) AppUi.ACCENT else AppUi.WARN)
    }

    private fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, uiHandler)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    private fun margin(topDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = AppUi.dp(this@PracticeActivity, topDp) }

    companion object {
        private const val REQUEST_MICROPHONE = 20
        private const val SAMPLE_RATE = 22050
        private const val FRAME_SIZE = 2048
        private const val HOP_SIZE = 512
        private const val MAX_MIC_LATENCY_MS = 500
        private const val REFERENCE_OFFSET_RANGE_MS = 2000
        private const val SYNC_TOLERANCE_MS = 100
        private const val NOTE_TOLERANCE_SEMITONES = 0.5f
    }
}
