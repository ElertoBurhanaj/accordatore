package com.example.cantonuovo

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

object AudioDecoder {
    const val TARGET_SAMPLE_RATE = 22050
    private const val MAX_DURATION_SECONDS = 10 * 60

    data class Pcm(val samples: FloatArray, val sampleRate: Int)

    private class Samples {
        private var values = FloatArray(16_384)
        var size = 0
            private set

        fun add(value: Float) {
            check(size < TARGET_SAMPLE_RATE * MAX_DURATION_SECONDS) {
                "La traccia vocale non può superare 10 minuti"
            }
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }

    fun decode(file: File, onProgress: (Float) -> Unit): Pcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var selectedTrack = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    selectedTrack = index
                    format = candidate
                    break
                }
            }
            check(selectedTrack >= 0 && format != null) { "Il file non contiene una traccia audio" }
            extractor.selectTrack(selectedTrack)

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("Formato audio non riconosciuto")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(sampleRate in 8_000..192_000 && channels in 1..8) {
                "Frequenza di campionamento o numero di canali non supportato"
            }
            var bytesPerSample = 2
            var downsampleFactor = maxOf(1, sampleRate / TARGET_SAMPLE_RATE)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val samples = Samples()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var accumulated = 0f
            var accumulatedCount = 0

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: error("Impossibile leggere il buffer audio")
                        input.clear()
                        val bytes = extractor.readSampleData(input, 0)
                        if (bytes < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, bytes, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val output = codec.getOutputBuffer(outputIndex)
                        ?: error("Impossibile decodificare il buffer audio")
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    output.order(ByteOrder.nativeOrder())
                    if (bytesPerSample == 4) {
                        val buffer = output.asFloatBuffer()
                        while (buffer.remaining() >= channels) {
                            var mono = 0f
                            repeat(channels) { mono += buffer.get() }
                            accumulated += mono / channels
                            accumulatedCount++
                            if (accumulatedCount == downsampleFactor) {
                                samples.add(accumulated / accumulatedCount)
                                accumulated = 0f
                                accumulatedCount = 0
                            }
                        }
                    } else {
                        val buffer = output.asShortBuffer()
                        while (buffer.remaining() >= channels) {
                            var mono = 0f
                            repeat(channels) { mono += buffer.get() / 32768f }
                            accumulated += mono / channels
                            accumulatedCount++
                            if (accumulatedCount == downsampleFactor) {
                                samples.add(accumulated / accumulatedCount)
                                accumulated = 0f
                                accumulatedCount = 0
                            }
                        }
                    }
                    if (durationUs > 0L) {
                        onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decodedFormat = codec.outputFormat
                    sampleRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val isFloat = decodedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        decodedFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                        AudioFormat.ENCODING_PCM_FLOAT
                    bytesPerSample = if (isFloat) 4 else 2
                    downsampleFactor = maxOf(1, sampleRate / TARGET_SAMPLE_RATE)
                    accumulated = 0f
                    accumulatedCount = 0
                }
            }

            check(samples.size >= FRAME_SIZE) { "Il file audio è vuoto o troppo corto" }
            onProgress(1f)
            return Pcm(samples.toArray(), sampleRate / downsampleFactor)
        } finally {
            codec?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                    // The decoder may not have reached the started state.
                }
                it.release()
            }
            extractor.release()
        }
    }

    private const val FRAME_SIZE = 1024
}
