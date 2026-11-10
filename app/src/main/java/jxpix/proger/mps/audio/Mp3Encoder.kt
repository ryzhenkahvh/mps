package jxpix.proger.mps.audio

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.MPGLib
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Writes MP3 files. Android has no MP3 encoder of its own, so the app carries one:
 * jump3r, a Java port of LAME (LGPL). It needs nothing from Android and runs in plain JVM tests.
 */
object Mp3Encoder {

    val BIT_RATES = intArrayOf(128, 192, 256, 320)

    /** MPEG-1 Layer III, the kind of MP3 every player knows, exists only for these sample rates. */
    fun supports(sampleRate: Int): Boolean = sampleRate == 44100 || sampleRate == 48000 || sampleRate == 32000

    /**
     * Compresses a 16-bit WAV with a plain 44-byte header (as written by the offline renderer).
     *
     * @param title stored in the file as its name (ID3 tag), so players show it instead of the file name.
     * @param progress called with 0..1; return false to cancel.
     * @return false when cancelled; [out] is deleted in that case.
     */
    @Throws(IOException::class)
    fun encode(
        wav: File, out: File, sampleRate: Int, channels: Int = 2, kbps: Int = 192, title: String? = null,
        progress: (Float) -> Boolean = { true },
    ): Boolean {
        require(supports(sampleRate)) { "MP3 cannot hold $sampleRate Hz" }
        require(channels == 1 || channels == 2)

        // jump3r is LAME translated file by file: its parts have to be introduced to each other first
        val lame = Lame()
        val gain = GainAnalysis()
        val stream = BitStream()
        val presets = Presets()
        val quantizePvt = QuantizePVT()
        val quantize = Quantize()
        val vbr = VBRTag()
        val version = Version()
        val id3 = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        lame.setModules(gain, stream, presets, quantizePvt, quantize, vbr, version, id3, mpg)
        stream.setModules(gain, mpg, version, vbr)
        id3.setModules(stream, version)
        presets.setModules(lame)
        quantize.setModules(stream, reservoir, quantizePvt, takehiro)
        quantizePvt.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(stream)
        takehiro.setModules(quantizePvt)
        vbr.setModules(lame, stream, version)
        parse.setModules(version, id3, presets)

        val flags = lame.lame_init() ?: throw IOException("MP3 encoder could not start")
        flags.num_channels = channels
        flags.in_samplerate = sampleRate
        flags.brate = kbps
        flags.mode = if (channels == 2) MPEGMode.JOINT_STEREO else MPEGMode.MONO
        flags.quality = 3
        // a little headroom: compressed sound overshoots the peaks of the original slightly
        flags.scale = 0.97f
        flags.VBR = VbrMode.vbr_off
        flags.bWriteVbrTag = false
        flags.write_id3tag_automatic = false
        if (lame.lame_init_params(flags) < 0) throw IOException("MP3 encoder rejected $sampleRate Hz, $kbps kbps")

        val frames = 4096
        val left = IntArray(frames)
        val right = IntArray(frames)
        // always a multiple of four bytes, so a read never ends inside a frame
        val pcm = ByteArray(frames * 2 * channels)
        val mp3 = ByteArray(frames * 5 / 4 + 7200 + 4096)
        var ok = false
        val input = RandomAccessFile(wav, "r")
        val output = BufferedOutputStream(FileOutputStream(out), 1 shl 16)
        try {
            if (!title.isNullOrBlank()) output.write(id3v2(title))
            input.seek(44)
            val total = (wav.length() - 44).coerceAtLeast(1)
            var read = 0L
            while (true) {
                val n = readFully(input, pcm)
                if (n <= 0) break
                val count = n / (2 * channels)
                var o = 0
                for (i in 0 until count) {
                    // the encoder wants the samples spread over the whole 32-bit range
                    left[i] = ((pcm[o].toInt() and 0xff) or (pcm[o + 1].toInt() shl 8)) shl 16
                    o += 2
                    if (channels == 2) {
                        right[i] = ((pcm[o].toInt() and 0xff) or (pcm[o + 1].toInt() shl 8)) shl 16
                        o += 2
                    }
                }
                val written = lame.lame_encode_buffer_int(flags, left, if (channels == 2) right else left, count, mp3, 0, mp3.size)
                if (written < 0) throw IOException("MP3 encoder error $written")
                output.write(mp3, 0, written)
                read += n
                if (!progress(read.toFloat() / total)) return false
            }
            val tail = lame.lame_encode_flush(flags, mp3, 0, mp3.size)
            if (tail < 0) throw IOException("MP3 encoder error $tail")
            output.write(mp3, 0, tail)
            ok = true
            return true
        } finally {
            input.close()
            try {
                output.close()
            } catch (_: IOException) {
                ok = false
            }
            lame.lame_close(flags)
            if (!ok) out.delete()
        }
    }

    private fun readFully(input: RandomAccessFile, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n <= 0) break
            total += n
        }
        // whole frames only (16-bit, one or two channels: 2 or 4 bytes, both divide the buffer)
        return total and 3.inv()
    }

    /** A minimal ID3v2.3 tag with the title (any language) and the name of the app. */
    private fun id3v2(title: String): ByteArray {
        fun frame(id: String, text: String): ByteArray {
            val body = byteArrayOf(1, 0xff.toByte(), 0xfe.toByte()) + text.take(120).toByteArray(Charsets.UTF_16LE)
            val head = ByteArray(10)
            for (i in 0 until 4) head[i] = id[i].code.toByte()
            head[4] = (body.size ushr 24).toByte()
            head[5] = (body.size ushr 16).toByte()
            head[6] = (body.size ushr 8).toByte()
            head[7] = body.size.toByte()
            return head + body
        }
        val frames = frame("TIT2", title) + frame("TSSE", "MPS")
        val head = ByteArray(10)
        head[0] = 'I'.code.toByte()
        head[1] = 'D'.code.toByte()
        head[2] = '3'.code.toByte()
        head[3] = 3
        // the size is written seven bits per byte
        val size = frames.size
        head[6] = ((size ushr 21) and 0x7f).toByte()
        head[7] = ((size ushr 14) and 0x7f).toByte()
        head[8] = ((size ushr 7) and 0x7f).toByte()
        head[9] = (size and 0x7f).toByte()
        return head + frames
    }
}
