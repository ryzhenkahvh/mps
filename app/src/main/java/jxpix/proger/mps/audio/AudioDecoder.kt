package jxpix.proger.mps.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import jxpix.proger.mps.engine.SampleData
import jxpix.proger.mps.engine.Wav
import java.io.IOException
import java.nio.ByteOrder

/** Turns any audio file Android can play (mp3, m4a, ogg, flac, wav...) into raw samples. */
object AudioDecoder {

    /** Longest audio we keep in memory. */
    const val MAX_SECONDS = 300

    @Throws(IOException::class)
    fun decode(context: Context, uri: Uri): SampleData {
        // Plain WAV files are read directly: faster and independent of the codecs on the device.
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(12)
                var got = 0
                while (got < 12) {
                    val n = input.read(head, got, 12 - got)
                    if (n < 0) break
                    got += n
                }
                if (got == 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                    String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE"
                ) {
                    context.contentResolver.openInputStream(uri)?.use { return limit(Wav.read(it)) }
                }
            }
        } catch (_: IOException) {
            // fall through to the platform decoder
        }
        return decodeWithCodec(context, uri)
    }

    private fun limit(data: SampleData): SampleData {
        val max = data.sampleRate.toLong() * MAX_SECONDS
        if (data.frames <= max) return data
        return SampleData(data.sampleRate, data.left.copyOf(max.toInt()), data.right?.copyOf(max.toInt()))
    }

    private fun decodeWithCodec(context: Context, uri: Uri): SampleData {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw IOException("No audio track")
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var capacity = rate * 10
            var left = ShortArray(capacity)
            var right: ShortArray? = if (channels >= 2) ShortArray(capacity) else null
            var frames = 0
            val maxFrames = rate.toLong() * MAX_SECONDS

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone && idle < 2000) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(5000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 5000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (ch != channels && frames == 0) {
                            channels = ch
                            right = if (channels >= 2) ShortArray(capacity) else null
                        }
                    }
                    outIndex >= 0 -> {
                        idle = 0
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val count = shorts.remaining() / channels
                            if (frames + count > capacity) {
                                capacity = maxOf(capacity * 2, frames + count)
                                left = left.copyOf(capacity)
                                right = right?.copyOf(capacity)
                            }
                            val r = right
                            for (i in 0 until count) {
                                left[frames + i] = shorts.get(i * channels)
                                if (r != null) r[frames + i] = shorts.get(i * channels + 1)
                            }
                            frames += count
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || frames >= maxFrames) outputDone = true
                    }
                    else -> idle++
                }
            }
            if (frames == 0) throw IOException("Could not decode audio")
            return SampleData(rate, left.copyOf(frames), right?.copyOf(frames))
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not decode audio", e)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }
}
