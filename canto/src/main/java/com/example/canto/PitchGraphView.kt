package com.example.canto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Grafico a scorrimento: le note della voce originale (barre blu) e quelle cantate
 * dall'utente (punti verdi se giuste, arancio se sbagliate). La linea bianca è "adesso".
 */
class PitchGraphView(context: Context) : View(context) {

    private class UserPoint(val tMs: Int, val midi: Float, val ok: Boolean)

    private val d = resources.displayMetrics.density

    private var track: PitchTrack? = null
    private var lo = 55f
    private var hi = 70f

    private val user = ArrayList<UserPoint>()

    private var anchorPos = 0
    private var anchorNano = 0L
    private var playing = false

    private val pastMs = 2000
    private val futureMs = 6000

    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5B7C99")
        strokeWidth = 12f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val refNowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A9BCCB")
        style = Paint.Style.FILL
    }
    private val userLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val gridNatural = Paint().apply {
        color = Color.parseColor("#2E4A62")
        strokeWidth = 1f * d
    }
    private val gridSharp = Paint().apply {
        color = Color.parseColor("#1F3248")
        strokeWidth = 1f * d
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * d
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A9BCCB")
        textSize = 11f * d
        textAlign = Paint.Align.RIGHT
    }

    private val green = Color.parseColor("#4CAF50")
    private val orange = Color.parseColor("#E9A23B")

    fun setTrack(t: PitchTrack) {
        track = t
        val r = t.displayRange()
        lo = r.first
        hi = r.second
        invalidate()
    }

    fun currentMs(): Int {
        return if (playing) anchorPos + ((System.nanoTime() - anchorNano) / 1_000_000L).toInt() else anchorPos
    }

    /** Sincronizza l'orologio del grafico con la posizione reale del lettore audio. */
    fun syncPosition(ms: Int, isPlaying: Boolean) {
        val estimate = currentMs()
        if (isPlaying != playing || abs(estimate - ms) > 80) {
            anchorPos = ms
            anchorNano = System.nanoTime()
        }
        playing = isPlaying
    }

    fun seekTo(ms: Int, isPlaying: Boolean) {
        anchorPos = ms
        anchorNano = System.nanoTime()
        playing = isPlaying
        user.removeAll { it.tMs >= ms }
        invalidate()
    }

    fun addUser(tMs: Int, midi: Float, ok: Boolean) {
        if (user.isNotEmpty() && tMs < user[user.size - 1].tMs) return
        user.add(UserPoint(tMs, midi, ok))
    }

    fun clearUser() {
        user.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val labelW = 44f * d
        val plotL = labelW
        val plotW = w - labelW
        val padV = 10f * d
        val plotH = h - 2f * padV

        val now = currentMs()
        val t0 = now - pastMs
        val totalMs = (pastMs + futureMs).toFloat()

        fun xOf(t: Int): Float = plotL + (t - t0) / totalMs * plotW
        fun yOf(m: Float): Float = (padV + (hi - m) / (hi - lo) * plotH).coerceIn(padV, h - padV)

        // Righe delle note
        val first = ceil(lo).toInt()
        val last = floor(hi).toInt()
        for (m in first..last) {
            val pc = ((m % 12) + 12) % 12
            val natural = pc == 0 || pc == 2 || pc == 4 || pc == 5 || pc == 7 || pc == 9 || pc == 11
            val y = yOf(m.toFloat())
            canvas.drawLine(plotL, y, w, y, if (natural) gridNatural else gridSharp)
            if (natural) {
                val octave = Math.floorDiv(m, 12) - 1
                canvas.drawText(Notes.pitchClassName(pc) + octave, labelW - 6f * d, y + 4f * d, labelPaint)
            }
        }

        // Voce originale
        val tr = track
        if (tr != null) {
            val hopMs = tr.hopSec * 1000f
            val i0 = maxOf(0, (t0 / hopMs).toInt() - 1)
            val i1 = minOf(tr.midi.size - 1, ((t0 + totalMs) / hopMs).toInt() + 1)
            var i = i0
            while (i < i1) {
                val a = tr.midi[i]
                val b = tr.midi[i + 1]
                if (!a.isNaN()) {
                    val xa = xOf((i * hopMs).toInt())
                    val ya = yOf(a)
                    if (!b.isNaN() && abs(b - a) < 4f) {
                        canvas.drawLine(xa, ya, xOf(((i + 1) * hopMs).toInt()), yOf(b), refPaint)
                    } else {
                        canvas.drawPoint(xa, ya, refPaint)
                    }
                }
                i++
            }
            val cur = tr.nearestVoiced(now / 1000f, 1)
            if (!cur.isNaN()) {
                canvas.drawCircle(xOf(now), yOf(cur), 9f * d, refNowPaint)
            }
        }

        // Voce dell'utente
        if (user.isNotEmpty()) {
            var s = user.size - 1
            while (s > 0 && user[s - 1].tMs >= t0) s--
            var prev: UserPoint? = null
            for (k in s until user.size) {
                val p = user[k]
                if (p.tMs > now + 200) break
                userLinePaint.color = if (p.ok) green else orange
                val x = xOf(p.tMs)
                val y = yOf(p.midi)
                if (prev != null && p.tMs - prev.tMs <= 120 && abs(p.midi - prev.midi) <= 2f) {
                    canvas.drawLine(xOf(prev.tMs), yOf(prev.midi), x, y, userLinePaint)
                } else {
                    canvas.drawCircle(x, y, 3f * d, userLinePaint)
                }
                prev = p
            }
        }

        // Linea "adesso"
        val nx = xOf(now)
        canvas.drawLine(nx, 0f, nx, h, nowPaint)
    }
}
