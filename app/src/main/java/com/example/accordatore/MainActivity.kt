package com.example.accordatore

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ln

class MainActivity : Activity() {

    private data class StringTarget(val name: String, val freq: Double)
    private data class Tuning(val label: String, val strings: List<StringTarget>)

    private val tunings = listOf(
        Tuning(
            "Standard (E A D G B E)", listOf(
                StringTarget("E2", 82.41), StringTarget("A2", 110.00),
                StringTarget("D3", 146.83), StringTarget("G3", 196.00),
                StringTarget("B3", 246.94), StringTarget("E4", 329.63)
            )
        ),
        Tuning(
            "Drop D (D A D G B E)", listOf(
                StringTarget("D2", 73.42), StringTarget("A2", 110.00),
                StringTarget("D3", 146.83), StringTarget("G3", 196.00),
                StringTarget("B3", 246.94), StringTarget("E4", 329.63)
            )
        ),
        Tuning(
            "DADGAD", listOf(
                StringTarget("D2", 73.42), StringTarget("A2", 110.00),
                StringTarget("D3", 146.83), StringTarget("G3", 196.00),
                StringTarget("A3", 220.00), StringTarget("D4", 293.66)
            )
        ),
        Tuning(
            "Mezzo tono sotto (Eb Ab Db Gb Bb Eb)", listOf(
                StringTarget("Eb2", 77.78), StringTarget("Ab2", 103.83),
                StringTarget("Db3", 138.59), StringTarget("Gb3", 185.00),
                StringTarget("Bb3", 233.08), StringTarget("Eb4", 311.13)
            )
        )
    )

    private var currentTuning = tunings[0]

    private lateinit var noteText: TextView
    private lateinit var freqText: TextView
    private lateinit var hintText: TextView
    private lateinit var tunerView: TunerView
    private lateinit var chipsRow: LinearLayout
    private val chips = mutableListOf<TextView>()

    @Volatile private var running = false
    private var audioThread: Thread? = null

    private val recent = ArrayDeque<Float>()
    private var silentFrames = 0
    private var smoothedCents = 0f
    private var hasSmoothed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                hintText.text = "Serve il permesso del microfono per accordare la chitarra"
            }
        }
    }

    // ---------- Interfaccia ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#16263A"))
            setPadding(dp(20), dp(24), dp(20), dp(24))
            fitsSystemWindows = true
        }

        val title = TextView(this).apply {
            text = "Accordatore"
            textSize = 22f
            setTextColor(Color.parseColor("#A9BCCB"))
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val spinner = Spinner(this)
        val names = tunings.map { it.label }
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, names
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentTuning = tunings[position]
                rebuildChips()
                resetDisplay()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        noteText = TextView(this).apply {
            text = "–"
            textSize = 96f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(noteText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })

        freqText = TextView(this).apply {
            text = "Suona una corda"
            textSize = 16f
            setTextColor(Color.parseColor("#A9BCCB"))
            gravity = Gravity.CENTER
        }
        root.addView(freqText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        tunerView = TunerView(this)
        root.addView(tunerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(120)).apply {
            topMargin = dp(16)
        })

        hintText = TextView(this).apply {
            text = ""
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(hintText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        root.addView(chipsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(32)
        })

        setContentView(root)
        rebuildChips()
    }

    private fun rebuildChips() {
        chipsRow.removeAllViews()
        chips.clear()
        for (target in currentTuning.strings) {
            val chip = TextView(this).apply {
                text = target.name
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2E4A62"))
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            chips.add(chip)
            chipsRow.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
    }

    private fun highlightChip(index: Int, color: Int) {
        for ((i, chip) in chips.withIndex()) {
            chip.setBackgroundColor(if (i == index) color else Color.parseColor("#2E4A62"))
        }
    }

    private fun resetDisplay() {
        recent.clear()
        silentFrames = 0
        hasSmoothed = false
        noteText.text = "–"
        noteText.setTextColor(Color.WHITE)
        freqText.text = "Suona una corda"
        hintText.text = ""
        tunerView.clear()
        highlightChip(-1, 0)
    }

    // ---------- Audio ----------

    private fun startListening() {
        if (running) return
        val sampleRate = 44100
        val frame = 4096
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frame * 4)
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

        running = true
        audioThread = Thread {
            val shorts = ShortArray(frame)
            val floats = FloatArray(frame)
            try {
                recorder.startRecording()
                while (running) {
                    var read = 0
                    while (read < frame && running) {
                        val n = recorder.read(shorts, read, frame - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < frame) {
                        if (!running) break
                        Thread.sleep(20)
                        continue
                    }
                    for (i in 0 until frame) floats[i] = shorts[i] / 32768f
                    val f = PitchDetector.detect(floats, sampleRate)
                    runOnUiThread { if (running) onPitch(f) }
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

    private fun stopListening() {
        running = false
        audioThread?.interrupt()
        audioThread = null
    }

    // ---------- Elaborazione del risultato ----------

    private fun onPitch(freq: Float) {
        if (freq <= 0f) {
            silentFrames++
            if (silentFrames > 8) resetDisplay()
            return
        }
        silentFrames = 0

        // Mediana degli ultimi valori per stabilizzare la lettura
        recent.addLast(freq)
        if (recent.size > 5) recent.removeFirst()
        val median = recent.sorted()[recent.size / 2].toDouble()

        // Corda più vicina all'accordatura scelta
        var bestIndex = 0
        var bestCents = Double.MAX_VALUE
        for ((i, target) in currentTuning.strings.withIndex()) {
            val c = 1200.0 * ln(median / target.freq) / ln(2.0)
            if (abs(c) < abs(bestCents)) {
                bestCents = c
                bestIndex = i
            }
        }
        val target = currentTuning.strings[bestIndex]
        val cents = bestCents.toFloat()

        smoothedCents = if (hasSmoothed) smoothedCents * 0.6f + cents * 0.4f else cents
        hasSmoothed = true

        noteText.text = target.name
        freqText.text = String.format("%.1f Hz  (obiettivo %.1f Hz)", median, target.freq)
        tunerView.setCents(smoothedCents)

        val absC = abs(smoothedCents)
        val color = when {
            absC <= 5f -> Color.parseColor("#4CAF50")
            absC <= 15f -> Color.parseColor("#E9A23B")
            else -> Color.parseColor("#C0362C")
        }
        noteText.setTextColor(color)
        highlightChip(bestIndex, color)
        hintText.text = when {
            absC <= 5f -> "Intonata!"
            smoothedCents < 0 -> "Troppo bassa: stringi la corda"
            else -> "Troppo alta: allenta la corda"
        }
    }

    companion object {
        private const val REQUEST_AUDIO = 1
    }
}
