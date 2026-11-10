package jxpix.proger.mps.data

import android.content.Context
import jxpix.proger.mps.engine.SampleData
import jxpix.proger.mps.engine.Wav
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/** One sound of the library, as shown in the list (the audio itself is loaded on demand). */
class SoundInfo(
    /** File name inside the library, e.g. "My kick.wav". */
    val file: String,
    val seconds: Float,
    val sampleRate: Int,
    val stereo: Boolean,
    val bytes: Long,
) {
    val title: String get() = file.substringBeforeLast('.')
}

/**
 * "My sounds": audio the user collected - drum hits, loops, recordings. It belongs to the app,
 * not to one project: a sound is copied into a project when it is used there, so projects stay
 * complete even if the sound is later removed from the library.
 *
 * Everything is kept as 16-bit WAV in files/sounds.
 */
class SoundLibrary(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, "sounds"))

    private fun file(name: String) = File(dir, File(name).name)

    fun list(): List<SoundInfo> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) } ?: return emptyList()
        val out = ArrayList<SoundInfo>(files.size)
        for (f in files) {
            val info = Wav.info(f) ?: continue
            out += SoundInfo(f.name, info.seconds, info.sampleRate, info.channels >= 2, f.length())
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        return out
    }

    /** Stores a sound and returns its file name; a number is appended when the title is taken. */
    @Throws(IOException::class)
    fun add(title: String, data: SampleData): String {
        dir.mkdirs()
        val safe = ProjectStore.safeSampleName(title)
        var name = "$safe.wav"
        var n = 2
        while (File(dir, name).exists()) name = "$safe $n.wav".also { n++ }
        val tmp = File(dir, "$name.tmp")
        try {
            Wav.write(tmp, data)
            if (!tmp.renameTo(File(dir, name))) throw IOException("Could not store $name")
        } finally {
            tmp.delete()
        }
        return name
    }

    /**
     * Copies a WAV file (a sound of a project) into the library and returns its name there.
     * A sound that is already in the library under the same title is not stored twice.
     */
    @Throws(IOException::class)
    fun adopt(source: File): String {
        dir.mkdirs()
        findSame(dir, source)?.let { return it }
        val safe = ProjectStore.safeSampleName(source.name.substringBeforeLast('.'))
        var name = "$safe.wav"
        var n = 2
        while (File(dir, name).exists()) name = "$safe $n.wav".also { n++ }
        source.copyTo(File(dir, name), overwrite = true)
        return name
    }

    fun load(name: String): SampleData? = try {
        val f = file(name)
        if (f.isFile) Wav.read(f) else null
    } catch (e: Exception) {
        null
    }

    /** @return the new file name, or null when the sound is gone or the title is taken. */
    fun rename(name: String, title: String): String? {
        val from = file(name)
        if (!from.isFile) return null
        val target = ProjectStore.safeSampleName(title) + ".wav"
        if (target == from.name) return target
        val to = File(dir, target)
        // on a file system that ignores case "Kick" -> "kick" is the same file: let it through
        if (to.exists() && !to.name.equals(from.name, ignoreCase = true)) return null
        return if (from.renameTo(to)) target else null
    }

    fun delete(name: String): Boolean = file(name).delete()

    fun fileOf(name: String): File = file(name)

    companion object {
        /** Name of a file in [dir] that holds exactly the same audio as [source], whatever it is called. */
        fun findSame(dir: File, source: File): String? {
            val size = source.length()
            val candidates = dir.listFiles { f -> f.isFile && f.length() == size && f.name.endsWith(".wav", ignoreCase = true) } ?: return null
            if (candidates.isEmpty()) return null
            val wanted = checksum(source)
            return candidates.sortedBy { it.name }.firstOrNull { checksum(it) == wanted }?.name
        }

        /** True when two files hold exactly the same bytes. */
        fun sameContent(a: File, b: File): Boolean {
            if (!a.isFile || !b.isFile || a.length() != b.length()) return false
            return checksum(a) == checksum(b)
        }

        private fun checksum(f: File): Long {
            val crc = CRC32()
            val buffer = ByteArray(1 shl 16)
            f.inputStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                }
            }
            return crc.value
        }
    }
}
