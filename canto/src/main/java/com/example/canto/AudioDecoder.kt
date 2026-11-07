package com.example.canto

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.max

/** Decodifica un file audio qualsiasi (mp3, m4a, ogg, wav, flac...) in PCM mono ridotto. */
object AudioDecoder {

    class Pcm(val samples: FloatArray, val sampleRate: Int)

    private class FloatList {
        var data = FloatArray(1 shl 20)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
    }

    fun decodeMono(path: String, targetRate: Int, onProgress: (Float) -> Unit): Pcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) throw IllegalStateException("Nessuna traccia audio nel file")
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var factor = max(1, sampleRate / targetRate)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var acc = 0f
            var accCount = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    outBuf.order(ByteOrder.nativeOrder())
                    if (isFloat) {
                        val fb = outBuf.asFloatBuffer()
                        while (fb.remaining() >= channels) {
                            var s = 0f
                            for (c in 0 until channels) s += fb.get()
                            acc += s / channels
                            accCount++
                            if (accCount == factor) {
                                out.add(acc / factor)
                                acc = 0f
                                accCount = 0
                            }
                        }
                    } else {
                        val sb = outBuf.asShortBuffer()
                        while (sb.remaining() >= channels) {
                            var s = 0f
                            for (c in 0 until channels) s += sb.get() / 32768f
                            acc += s / channels
                            accCount++
                            if (accCount == factor) {
                                out.add(acc / factor)
                                acc = 0f
                                accCount = 0
                            }
                        }
                    }
                    if (durationUs > 0) {
                        onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    factor = max(1, sampleRate / targetRate)
                    acc = 0f
                    accCount = 0
                }
            }

            if (out.size == 0) throw IllegalStateException("Il file audio sembra vuoto")
            return Pcm(out.data.copyOf(out.size), sampleRate / factor)
        } finally {
            try {
                codec?.stop()
            } catch (e: IllegalStateException) {
            }
            codec?.release()
            extractor.release()
        }
    }
}
