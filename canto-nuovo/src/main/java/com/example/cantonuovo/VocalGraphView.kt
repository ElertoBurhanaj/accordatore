package com.example.cantonuovo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class VocalGraphView(context: Context) : View(context) {
    private data class UserPoint(val timeMs: Int, val midi: Float, val accurate: Boolean)

    private val density = resources.displayMetrics.density
    private val userPoints = ArrayList<UserPoint>()
    private var track: VocalTrack? = null
    private var lowMidi = 48f
    private var highMidi = 84f
    private var positionMs = 0
    private var referenceOffsetMs = 0
    private val historyMs = 2400
    private val visibleDurationMs = 8000

    private val naturalGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x283E506F
        strokeWidth = density
    }
    private val accidentalGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x183E506F
        strokeWidth = density
    }
    private val referencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF63A8F5.toInt()
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDCE6F5.toInt()
        strokeWidth = 1.5f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFADB8CA.toInt()
        textSize = 11f * density
        textAlign = Paint.Align.RIGHT
    }

    fun setTrack(value: VocalTrack) {
        track = value
        value.range().let { (lowMidi, highMidi) ->
            this.lowMidi = lowMidi
            this.highMidi = highMidi
        }
        invalidate()
    }

    fun setPosition(valueMs: Int) {
        positionMs = valueMs.coerceAtLeast(0)
        invalidate()
    }

    fun setReferenceOffset(valueMs: Int) {
        referenceOffsetMs = valueMs
        invalidate()
    }

    fun addUserNote(timeMs: Int, midi: Float, accurate: Boolean) {
        if (!midi.isFinite()) return
        if (userPoints.isNotEmpty() && timeMs < userPoints.last().timeMs) return
        userPoints.add(UserPoint(timeMs, midi, accurate))
        val oldestVisible = positionMs - historyMs - 1500
        while (userPoints.isNotEmpty() && userPoints.first().timeMs < oldestVisible) {
            userPoints.removeAt(0)
        }
        invalidate()
    }

    fun clearUserNotes() {
        userPoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelWidth = 43f * density
        val left = labelWidth
        val right = width.toFloat()
        val top = 10f * density
        val bottom = height - top
        val plotHeight = (bottom - top).coerceAtLeast(1f)
        val plotWidth = (right - left).coerceAtLeast(1f)
        val startMs = positionMs - historyMs
        val endMs = startMs + visibleDurationMs

        fun x(timeMs: Int): Float =
            left + (timeMs - startMs).toFloat() / visibleDurationMs * plotWidth

        fun y(midi: Float): Float =
            (top + (highMidi - midi) / (highMidi - lowMidi) * plotHeight).coerceIn(top, bottom)

        val firstNote = ceil(lowMidi).toInt()
        val lastNote = floor(highMidi).toInt()
        for (note in firstNote..lastNote) {
            val pitchClass = Math.floorMod(note, 12)
            val natural = pitchClass in NATURAL_NOTES
            val noteY = y(note.toFloat())
            canvas.drawLine(left, noteY, right, noteY, if (natural) naturalGrid else accidentalGrid)
            if (natural) {
                canvas.drawText(
                    Notes.name(note.toFloat()),
                    labelWidth - 5f * density,
                    noteY + 4f * density,
                    labelPaint
                )
            }
        }

        track?.let { reference ->
            val hopMs = reference.hopSeconds * 1000f
            val firstFrame = maxOf(0, ((startMs - referenceOffsetMs) / hopMs).toInt() - 1)
            val lastFrame = minOf(
                reference.midi.lastIndex,
                ((endMs - referenceOffsetMs) / hopMs).toInt() + 1
            )
            for (index in firstFrame until lastFrame) {
                val current = reference.midi[index]
                if (!current.isFinite()) continue
                val next = reference.midi[index + 1]
                val currentTime = (index * hopMs).toInt() + referenceOffsetMs
                if (next.isFinite() && abs(next - current) < 4f) {
                    canvas.drawLine(
                        x(currentTime), y(current),
                        x(((index + 1) * hopMs).toInt() + referenceOffsetMs), y(next),
                        referencePaint
                    )
                } else {
                    canvas.drawCircle(x(currentTime), y(current), 3f * density, referencePaint)
                }
            }
        }

        var previous: UserPoint? = null
        userPoints.forEach { point ->
            if (point.timeMs > positionMs + 160 || point.timeMs < startMs) {
                previous = null
                return@forEach
            }
            userPaint.color = if (point.accurate) ACCURATE else OFF_PITCH
            val preceding = previous
            if (preceding != null && point.timeMs - preceding.timeMs < 120 &&
                abs(point.midi - preceding.midi) < 3f
            ) {
                canvas.drawLine(x(preceding.timeMs), y(preceding.midi), x(point.timeMs), y(point.midi), userPaint)
            } else {
                canvas.drawCircle(x(point.timeMs), y(point.midi), 3.2f * density, userPaint)
            }
            previous = point
        }

        val nowX = x(positionMs)
        canvas.drawLine(nowX, 0f, nowX, height.toFloat(), nowPaint)
    }

    companion object {
        private val NATURAL_NOTES = setOf(0, 2, 4, 5, 7, 9, 11)
        private const val ACCURATE = 0xFF55D6A7.toInt()
        private const val OFF_PITCH = 0xFFF2B45F.toInt()
    }
}
