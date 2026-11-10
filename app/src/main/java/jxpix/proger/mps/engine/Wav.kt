package jxpix.proger.mps.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Minimal, dependency-free WAV reader/writer (PCM 8/16/24/32-bit and 32-bit float). */
object Wav {

    @Throws(IOException::class)
    fun read(file: File): SampleData = file.inputStream().use { read(it) }

    @Throws(IOException::class)
    fun read(input: InputStream): SampleData {
        val d = DataInputStream(BufferedInputStream(input, 1 shl 16))
        if (fourCc(d) != "RIFF") throw IOException("Not a WAV file")
        le32(d)
        if (fourCc(d) != "WAVE") throw IOException("Not a WAV file")

        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        while (true) {
            val id = try {
                fourCc(d)
            } catch (e: EOFException) {
                throw IOException("WAV file has no audio data")
            }
            val size = le32(d).toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    format = le16(d)
                    channels = le16(d)
                    rate = le32(d)
                    le32(d)
                    le16(d)
                    bits = le16(d)
                    var rest = size - 16
                    if (format == 0xFFFE && rest >= 24) {
                        // WAVE_FORMAT_EXTENSIBLE: the real format is the first two bytes of the GUID
                        le16(d); le16(d); le32(d)
                        format = le16(d)
                        skip(d, 14)
                        rest -= 24
                    }
                    skip(d, rest + (size and 1))
                }
                "data" -> {
                    if (channels < 1 || rate < 1 || bits < 8) throw IOException("Unsupported WAV format")
                    if (format != 1 && format != 3) throw IOException("Compressed WAV files are not supported")
                    val bytesPerSample = bits / 8
                    val frames = (size / (bytesPerSample * channels)).toInt().coerceAtMost(MAX_FRAMES)
                    val left = ShortArray(frames)
                    val right = if (channels >= 2) ShortArray(frames) else null
                    val frame = ByteArray(bytesPerSample * channels)
                    var read = 0
                    try {
                        while (read < frames) {
                            d.readFully(frame)
                            left[read] = decode(frame, 0, bytesPerSample, format)
                            if (right != null) right[read] = decode(frame, bytesPerSample, bytesPerSample, format)
                            read++
                        }
                    } catch (e: EOFException) {
                        // truncated file: keep what we have
                        return SampleData(rate, left.copyOf(read), right?.copyOf(read))
                    }
                    return SampleData(rate, left, right)
                }
                else -> skip(d, size + (size and 1))
            }
        }
    }

    private fun decode(b: ByteArray, o: Int, bytes: Int, format: Int): Short = when {
        format == 3 && bytes == 4 -> {
            val bitsValue = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or (b[o + 3].toInt() shl 24)
            (Float.fromBits(bitsValue).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
        }
        bytes == 1 -> (((b[o].toInt() and 0xFF) - 128) shl 8).toShort()
        // 16/24/32-bit integer: the two most significant bytes are all we keep
        else -> ((b[o + bytes - 2].toInt() and 0xFF) or (b[o + bytes - 1].toInt() shl 8)).toShort()
    }

    @Throws(IOException::class)
    fun write(file: File, data: SampleData) {
        val w = Writer(file, data.sampleRate, if (data.isStereo) 2 else 1)
        try {
            val r = data.right
            val chunk = ShortArray(8192)
            var i = 0
            while (i < data.frames) {
                var n = 0
                while (n < chunk.size - 1 && i < data.frames) {
                    chunk[n++] = data.left[i]
                    if (r != null) chunk[n++] = r[i]
                    i++
                }
                w.write(chunk, n)
            }
        } finally {
            w.close()
        }
    }

    /** Streaming 16-bit WAV writer; the header is patched with the final size on [close]. */
    class Writer(private val file: File, val sampleRate: Int, val channels: Int) {
        private val raf = RandomAccessFile(file, "rw")
        private var bytes = ByteArray(1 shl 14)
        private var dataBytes = 0L

        /** Number of frames written so far. */
        val frames: Long get() = dataBytes / (2 * channels)

        init {
            raf.setLength(0)
            raf.write(header(sampleRate, channels, 0))
        }

        /** Writes [count] interleaved 16-bit samples. */
        fun write(samples: ShortArray, count: Int) {
            if (bytes.size < count * 2) bytes = ByteArray(count * 2)
            var o = 0
            for (i in 0 until count) {
                val v = samples[i].toInt()
                bytes[o++] = v.toByte()
                bytes[o++] = (v shr 8).toByte()
            }
            raf.write(bytes, 0, o)
            dataBytes += o
        }

        /** Writes [count] interleaved float samples (-1..1), clipped and converted to 16-bit. */
        fun write(samples: FloatArray, count: Int) {
            if (bytes.size < count * 2) bytes = ByteArray(count * 2)
            var o = 0
            for (i in 0 until count) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                bytes[o++] = v.toByte()
                bytes[o++] = (v shr 8).toByte()
            }
            raf.write(bytes, 0, o)
            dataBytes += o
        }

        fun close() {
            try {
                raf.seek(0)
                raf.write(header(sampleRate, channels, dataBytes))
            } finally {
                raf.close()
            }
        }
    }

    /** Writes a complete 16-bit WAV to a (non seekable) stream. */
    @Throws(IOException::class)
    fun copyWithHeader(out: OutputStream, pcm16: InputStream, dataBytes: Long, sampleRate: Int, channels: Int) {
        val o = BufferedOutputStream(out, 1 shl 16)
        o.write(header(sampleRate, channels, dataBytes))
        pcm16.copyTo(o)
        o.flush()
    }

    const val HEADER_SIZE = 44
    private const val MAX_FRAMES = 48000 * 60 * 20 // 20 minutes at 48 kHz

    private fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val h = ByteArray(HEADER_SIZE)
        fun str(o: Int, s: String) { for (i in s.indices) h[o + i] = s[i].code.toByte() }
        fun i32(o: Int, v: Long) { for (i in 0 until 4) h[o + i] = (v shr (8 * i)).toByte() }
        fun i16(o: Int, v: Int) { h[o] = v.toByte(); h[o + 1] = (v shr 8).toByte() }
        str(0, "RIFF"); i32(4, 36 + dataBytes); str(8, "WAVE")
        str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, channels)
        i32(24, sampleRate.toLong()); i32(28, sampleRate.toLong() * channels * 2)
        i16(32, channels * 2); i16(34, 16)
        str(36, "data"); i32(40, dataBytes)
        return h
    }

    private fun fourCc(d: DataInputStream): String {
        val b = ByteArray(4)
        d.readFully(b)
        return String(b, Charsets.ISO_8859_1)
    }

    private fun le16(d: DataInputStream): Int {
        val a = d.readUnsignedByte()
        val b = d.readUnsignedByte()
        return a or (b shl 8)
    }

    private fun le32(d: DataInputStream): Int {
        val a = d.readUnsignedByte()
        val b = d.readUnsignedByte()
        val c = d.readUnsignedByte()
        val e = d.readUnsignedByte()
        return a or (b shl 8) or (c shl 16) or (e shl 24)
    }

    private fun skip(d: DataInputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = d.skip(left)
            if (n <= 0) {
                if (d.read() < 0) throw EOFException()
                left--
            } else left -= n
        }
    }
}
