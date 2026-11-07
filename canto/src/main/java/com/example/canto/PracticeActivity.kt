package com.example.canto

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

class PracticeActivity : Activity() {

    private var ready = false

    private lateinit var song: Song
    private lateinit var track: PitchTrack
    private lateinit var player: MediaPlayer
    private var guidePlayer: MediaPlayer? = null

    private lateinit var graph: PitchGraphView
    private lateinit var infoText: TextView
    private lateinit var hintText: TextView
    private lateinit var scoreText: TextView
    private lateinit var timeText: TextView
    private lateinit var positionBar: SeekBar
    private lateinit var playButton: Button
    private lateinit var latencyText: TextView

    private var ignoreOctave = true
    private var latencyMs = 120
    private var dragging = false

    private var alive = false
    private var lastShownSecond = -1
    private var tickCount = 0

    // Microfono
    @Volatile private var micRunning = false
    private var micThread: Thread? = null
    private val recentUser = ArrayDeque<Float>()

    // Punteggio
    private var scoredFrames = 0
    private var goodFrames = 0

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getStringExtra(EXTRA_SONG_ID)
        val found = SongStore(this).load().firstOrNull { it.id == id }
        if (found == null) {
            finish()
            return
        }
        try {
            song = found
            track = PitchTrack.load(File(found.pitchPath))
            player = MediaPlayer().apply {
                setDataSource(found.instrumentalPath)
                prepare()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Impossibile aprire la canzone: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        player.setOnCompletionListener {
            guidePlayer?.pause()
            playButton.text = "▶  Avvia"
        }

        buildUi()
        graph.setTrack(track)
        graph.seekTo(0, false)
        ready = true

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    // ---------- Interfaccia ----------

    private fun buildUi() {
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#16263A"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            fitsSystemWindows = true
        }

        root.addView(TextView(this).apply {
            text = song.title
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(full, wrap))

        graph = PitchGraphView(this)
        root.addView(graph, LinearLayout.LayoutParams(full, 0, 1f).apply { topMargin = dp(4) })

        infoText = TextView(this).apply {
            text = "Originale: –    Tu: –"
            textSize = 16f
            setTextColor(Color.parseColor("#A9BCCB"))
            gravity = Gravity.CENTER
        }
        root.addView(infoText, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(4) })

        hintText = TextView(this).apply {
            text = ""
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(hintText, LinearLayout.LayoutParams(full, wrap))

        scoreText = TextView(this).apply {
            text = "Precisione: –"
            textSize = 14f
            setTextColor(Color.parseColor("#A9BCCB"))
            gravity = Gravity.CENTER
        }
        root.addView(scoreText, LinearLayout.LayoutParams(full, wrap))

        // Posizione
        val posRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeText = TextView(this).apply {
            text = "0:00 / " + LibraryActivity.formatTime(player.duration)
            textSize = 13f
            setTextColor(Color.parseColor("#A9BCCB"))
        }
        positionBar = SeekBar(this).apply {
            max = player.duration
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeText.text = LibraryActivity.formatTime(progress) + " / " + LibraryActivity.formatTime(player.duration)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    dragging = true
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    seekAll(sb.progress)
                }
            })
        }
        posRow.addView(positionBar, LinearLayout.LayoutParams(0, wrap, 1f))
        posRow.addView(timeText, LinearLayout.LayoutParams(wrap, wrap))
        root.addView(posRow, LinearLayout.LayoutParams(full, wrap))

        // Pulsanti
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playButton = Button(this).apply {
            text = "▶  Avvia"
            setOnClickListener { togglePlay() }
        }
        buttons.addView(playButton, LinearLayout.LayoutParams(0, wrap, 1f))
        buttons.addView(Button(this).apply {
            text = "⏮  Da capo"
            setOnClickListener {
                seekAll(0)
                resetScore()
            }
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        root.addView(buttons, LinearLayout.LayoutParams(full, wrap))

        // Opzioni
        root.addView(Switch(this).apply {
            text = "Ignora l'ottava (confronta solo la nota)"
            setTextColor(Color.WHITE)
            isChecked = true
            setOnCheckedChangeListener { _, checked -> ignoreOctave = checked }
        }, LinearLayout.LayoutParams(full, wrap))

        root.addView(Switch(this).apply {
            text = "Ascolta anche la voce originale"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> setGuide(checked) }
        }, LinearLayout.LayoutParams(full, wrap))

        val latRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        latencyText = TextView(this).apply {
            text = "Ritardo: $latencyMs ms"
            textSize = 13f
            setTextColor(Color.parseColor("#A9BCCB"))
        }
        latRow.addView(latencyText, LinearLayout.LayoutParams(dp(110), wrap))
        latRow.addView(SeekBar(this).apply {
            max = 400
            progress = latencyMs
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    latencyMs = progress
                    latencyText.text = "Ritardo: $latencyMs ms"
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        root.addView(latRow, LinearLayout.LayoutParams(full, wrap))

        setContentView(root)
    }

    // ---------- Riproduzione ----------

    private fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            guidePlayer?.pause()
            playButton.text = "▶  Avvia"
            graph.syncPosition(player.currentPosition, false)
        } else {
            if (player.currentPosition >= player.duration - 200) seekAll(0)
            player.start()
            guidePlayer?.start()
            playButton.text = "⏸  Pausa"
            graph.syncPosition(player.currentPosition, true)
        }
    }

    private fun seekAll(ms: Int) {
        val target = ms.coerceIn(0, player.duration)
        player.seekTo(target)
        guidePlayer?.seekTo(target)
        graph.seekTo(target, player.isPlaying)
        positionBar.progress = target
        timeText.text = LibraryActivity.formatTime(target) + " / " + LibraryActivity.formatTime(player.duration)
    }

    private fun setGuide(on: Boolean) {
        if (on) {
            try {
                val gp = MediaPlayer()
                gp.setDataSource(song.vocalPath)
                gp.prepare()
                gp.seekTo(player.currentPosition)
                if (player.isPlaying) gp.start()
                guidePlayer = gp
            } catch (e: Exception) {
                Toast.makeText(this, "Impossibile riprodurre la voce originale", Toast.LENGTH_SHORT).show()
            }
        } else {
            guidePlayer?.release()
            guidePlayer = null
        }
    }

    private fun resetScore() {
        scoredFrames = 0
        goodFrames = 0
        scoreText.text = "Precisione: –"
    }

    // ---------- Ciclo di aggiornamento ----------

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!alive) return
            tick()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun tick() {
        val playing = player.isPlaying
        val pos = player.currentPosition
        graph.syncPosition(pos, playing)
        graph.invalidate()

        tickCount++
        val gp = guidePlayer
        if (gp != null && playing && tickCount % 60 == 0 && abs(gp.currentPosition - pos) > 120) {
            gp.seekTo(pos)
        }

        val sec = pos / 1000
        if (sec != lastShownSecond && !dragging) {
            lastShownSecond = sec
            positionBar.progress = pos
            timeText.text = LibraryActivity.formatTime(pos) + " / " + LibraryActivity.formatTime(player.duration)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        alive = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMic()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!ready) return
        alive = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        stopMic()
        if (player.isPlaying) {
            player.pause()
            guidePlayer?.pause()
            playButton.text = "▶  Avvia"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ready) {
            player.release()
            guidePlayer?.release()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (alive) startMic()
            } else {
                hintText.text = "Serve il permesso del microfono"
            }
        }
    }

    // ---------- Microfono ----------

    private fun startMic() {
        if (micRunning) return
        val sampleRate = 44100
        val hopSize = 1024
        val windowSize = 2048
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, hopSize * 8)
            )
        } catch (e: SecurityException) {
            hintText.text = "Permesso del microfono negato"
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            hintText.text = "Impossibile usare il microfono"
            return
        }

        micRunning = true
        micThread = Thread {
            val window = FloatArray(windowSize)
            val chunk = ShortArray(hopSize)
            try {
                recorder.startRecording()
                while (micRunning) {
                    var read = 0
                    while (read < hopSize && micRunning) {
                        val n = recorder.read(chunk, read, hopSize - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < hopSize) {
                        if (!micRunning) break
                        Thread.sleep(20)
                        continue
                    }
                    System.arraycopy(window, hopSize, window, 0, windowSize - hopSize)
                    for (i in 0 until hopSize) window[windowSize - hopSize + i] = chunk[i] / 32768f
                    val f = PitchDetector.detect(window, sampleRate, 70f, 1000f, 0.01f)
                    runOnUiThread { if (micRunning) onUserPitch(f) }
                }
            } catch (e: InterruptedException) {
                // chiusura normale
            } finally {
                try {
                    recorder.stop()
                } catch (e: IllegalStateException) {
                }
                recorder.release()
            }
        }.also { it.start() }
    }

    private fun stopMic() {
        micRunning = false
        micThread?.interrupt()
        micThread = null
    }

    // ---------- Confronto con la voce originale ----------

    private fun onUserPitch(freq: Float) {
        if (freq <= 0f) {
            recentUser.clear()
            infoText.text = "Originale: " + refName(graph.currentMs()) + "    Tu: –"
            hintText.text = ""
            return
        }

        val raw = (69.0 + 12.0 * ln(freq / 440.0) / ln(2.0)).toFloat()
        recentUser.addLast(raw)
        if (recentUser.size > 3) recentUser.removeFirst()
        var m = recentUser.sorted()[recentUser.size / 2]

        val tMs = graph.currentMs() - latencyMs - 23
        val ref = track.nearestVoiced(tMs / 1000f, 3)
        if (ignoreOctave && !ref.isNaN()) {
            m += 12f * Math.round((ref - m) / 12f)
        }
        val ok = !ref.isNaN() && abs(m - ref) <= 0.5f

        if (player.isPlaying) {
            graph.addUser(tMs, m, ok)
            if (!ref.isNaN()) {
                scoredFrames++
                if (ok) goodFrames++
                scoreText.text = "Precisione: " + (100 * goodFrames / scoredFrames) + "%"
            }
        }

        val cents = ((m - m.roundToInt()) * 100).roundToInt()
        infoText.text = "Originale: " + (if (ref.isNaN()) "–" else Notes.name(ref)) +
            "    Tu: " + Notes.name(m) + " (" + (if (cents >= 0) "+" else "") + cents + " cent)"

        if (ref.isNaN()) {
            hintText.text = ""
        } else {
            val diff = ref - m
            hintText.text = when {
                abs(diff) <= 0.5f -> "Perfetto!"
                diff > 0 -> "Più acuta ↑ (" + abs(diff).roundToInt() + " semitoni)"
                else -> "Più grave ↓ (" + abs(diff).roundToInt() + " semitoni)"
            }
            hintText.setTextColor(
                when {
                    abs(diff) <= 0.5f -> Color.parseColor("#4CAF50")
                    abs(diff) <= 1.5f -> Color.parseColor("#E9A23B")
                    else -> Color.parseColor("#C0362C")
                }
            )
        }
    }

    private fun refName(ms: Int): String {
        val ref = track.nearestVoiced(ms / 1000f, 3)
        return if (ref.isNaN()) "–" else Notes.name(ref)
    }

    companion object {
        const val EXTRA_SONG_ID = "song_id"
        private const val REQUEST_AUDIO = 1
    }
}
