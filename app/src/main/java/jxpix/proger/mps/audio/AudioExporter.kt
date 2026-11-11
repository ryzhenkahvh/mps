package jxpix.proger.mps.audio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** The kinds of audio file a track can be exported as. */
enum class ExportFormat(val extension: String, val mime: String) {
    /** Uncompressed. */
    WAV("wav", "audio/x-wav"),

    /** Plays everywhere. */
    MP3("mp3", "audio/mpeg"),

    /** AAC in an MPEG-4 file. */
    M4A("m4a", "audio/mp4"),
}

/** Saves rendered audio to the phone's Music folder and shares it with other apps. */
object AudioExporter {

    const val FOLDER = "MPS"

    /** Copies [file] into Music/MPS (or a folder inside it) and returns its public Uri. */
    @Throws(IOException::class)
    fun saveToMusic(context: Context, file: File, displayName: String, mime: String, subFolder: String? = null): Uri {
        if (Build.VERSION.SDK_INT < 29) return saveToMusicLegacy(context, file, displayName, mime, subFolder)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER + if (subFolder != null) "/$subFolder" else "")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the file")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: throw IOException("Could not open the file")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw if (e is IOException) e else IOException(e)
        }
        return uri
    }

    /** True where saving to the Music folder needs the user's permission first: Android 9 only. */
    val needsStoragePermission: Boolean get() = Build.VERSION.SDK_INT < 29

    /**
     * Android 9 has no shared collections to write into: the file is copied into Music/MPS directly
     * (the user has allowed that, see [needsStoragePermission]) and then made known to the media library.
     * A file with the same name is never overwritten.
     */
    @Suppress("DEPRECATION")
    @Throws(IOException::class)
    private fun saveToMusicLegacy(context: Context, file: File, displayName: String, mime: String, subFolder: String?): Uri {
        val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val dir = File(music, if (subFolder != null) "$FOLDER/$subFolder" else FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the folder")
        val base = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', "")
        var target = File(dir, displayName)
        var n = 2
        while (target.exists()) target = File(dir, "$base ($n)" + if (ext.isEmpty()) "" else ".$ext").also { n++ }
        try {
            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it, 1 shl 16) } }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DATA, target.absolutePath)
                put(MediaStore.Audio.Media.DISPLAY_NAME, target.name)
                put(MediaStore.Audio.Media.TITLE, target.nameWithoutExtension)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            return context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not add the file to the media library")
        } catch (e: Exception) {
            target.delete()
            throw if (e is IOException) e else IOException(e)
        }
    }

    /** One file, or several at once (the separate tracks of a song). */
    fun shareIntent(uris: List<Uri>, mime: String, title: String): Intent {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title)
    }

    fun delete(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    /**
     * Compresses a 16-bit stereo WAV (as written by the offline renderer) to AAC in an .m4a file,
     * roughly ten times smaller - handy for sending a track in a messenger.
     */
    @Throws(IOException::class)
    fun encodeAac(wav: File, out: File, sampleRate: Int, bitRate: Int = 256_000, progress: (Float) -> Boolean = { true }): Boolean {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        val input = RandomAccessFile(wav, "r")
        var ok = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            input.seek(44)
            val total = (wav.length() - 44).coerceAtLeast(1)
            val chunk = ByteArray(1 shl 14)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var started = false
            var readBytes = 0L
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone && idle < 4000) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(5000)
                    if (inIndex >= 0) {
                        val buf: ByteBuffer = codec.getInputBuffer(inIndex)!!
                        buf.clear()
                        val want = minOf(chunk.size, buf.remaining()) and 3.inv()
                        val n = input.read(chunk, 0, want)
                        val pts = readBytes / 4 * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(chunk, 0, n)
                            codec.queueInputBuffer(inIndex, 0, n, pts, 0)
                            readBytes += n
                            if (!progress(readBytes.toFloat() / total)) return false
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 5000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                    outIndex >= 0 -> {
                        idle = 0
                        val buf = codec.getOutputBuffer(outIndex)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buf != null && info.size > 0 && started && !config) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    else -> idle++
                }
            }
            ok = outputDone && started
            return ok
        } finally {
            input.close()
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
            try {
                muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            if (!ok) out.delete()
        }
    }
}
