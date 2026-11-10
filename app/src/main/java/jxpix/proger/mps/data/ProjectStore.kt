package jxpix.proger.mps.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import jxpix.proger.mps.model.usedSamples
import android.content.Context
import android.util.Log
import jxpix.proger.mps.engine.SampleData
import jxpix.proger.mps.engine.Wav
import jxpix.proger.mps.model.Project
import jxpix.proger.mps.model.sanitized
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/** What the home screen needs to know about a saved project (kept in a tiny side file). */
@Serializable
data class ProjectInfo(
    val id: String = "",
    val name: String = "",
    val bpm: Float = 120f,
    val modified: Long = 0L,
    val seconds: Float = 0f,
    val channels: Int = 0,
    /** Colour indexes of the first channels, drawn as a small preview on the project card. */
    val colors: List<Int> = emptyList(),
)

/**
 * Projects live in the app's private storage, one folder each:
 *
 *     files/projects/<id>/project.json   the song
 *     files/projects/<id>/meta.json      name, tempo, date (for the list)
 *     files/projects/<id>/samples/       recordings and imported audio (wav)
 */
class ProjectStore(private val root: File) {

    constructor(context: Context) : this(File(context.filesDir, "projects"))

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    private fun dir(id: String) = File(root, id)
    private fun projectFile(id: String) = File(dir(id), "project.json")
    private fun metaFile(id: String) = File(dir(id), "meta.json")

    fun samplesDir(id: String): File = File(dir(id), "samples").also { it.mkdirs() }

    fun list(): List<ProjectInfo> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val out = ArrayList<ProjectInfo>()
        for (d in dirs) {
            val id = d.name
            if (!projectFile(id).exists()) continue
            val info = try {
                json.decodeFromString(ProjectInfo.serializer(), metaFile(id).readText())
            } catch (e: Exception) {
                // the side file is missing or broken: rebuild it from the project itself
                load(id)?.let { info(id, it) } ?: continue
            }
            out += info.copy(id = id)
        }
        return out.sortedByDescending { it.modified }
    }

    fun load(id: String): Project? = try {
        json.decodeFromString(Project.serializer(), projectFile(id).readText()).sanitized()
    } catch (e: Exception) {
        Log.e(TAG, "Could not load project $id", e)
        null
    }

    /** Creates the folder for a new project and returns its id. */
    fun create(project: Project): String {
        val id = newId()
        save(id, project)
        return id
    }

    private fun newId(): String = UUID.randomUUID().toString().substring(0, 12)

    @Synchronized
    fun save(id: String, project: Project) {
        try {
            dir(id).mkdirs()
            writeAtomic(projectFile(id), json.encodeToString(Project.serializer(), project))
            writeAtomic(metaFile(id), json.encodeToString(ProjectInfo.serializer(), info(id, project)))
        } catch (e: Exception) {
            Log.e(TAG, "Could not save project $id", e)
        }
    }

    private fun info(id: String, p: Project) = ProjectInfo(
        id = id,
        name = p.name,
        bpm = p.bpm,
        modified = System.currentTimeMillis(),
        seconds = (p.songEnd * p.secondsPerTick).toFloat(),
        channels = p.channels.size,
        colors = p.channels.take(12).map { it.color },
    )

    /** Writes to a temporary file first so that a crash can never leave a half-written project. */
    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not replace ${file.name}")
        }
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun duplicate(id: String, newName: String): String? {
        val project = load(id) ?: return null
        val newId = newId()
        return try {
            dir(id).copyRecursively(dir(newId), overwrite = true)
            save(newId, project.copy(name = newName))
            newId
        } catch (e: Exception) {
            dir(newId).deleteRecursively()
            null
        }
    }

    fun rename(id: String, name: String) {
        val project = load(id) ?: return
        save(id, project.copy(name = name))
    }

    // ---------------------------------------------------------------- project files

    /**
     * Writes a project together with the audio it uses into one file: a zip archive, saved
     * with the extension .mps. It is a backup and the way to move a project to another phone.
     */
    @Throws(IOException::class)
    fun exportTo(id: String, out: OutputStream) {
        val project = load(id) ?: throw IOException("Project not found")
        ZipOutputStream(BufferedOutputStream(out, 1 shl 16)).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_INFO))
            zip.write("{\"app\":\"MPS\",\"format\":$FILE_FORMAT}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(ENTRY_PROJECT))
            zip.write(json.encodeToString(Project.serializer(), project).toByteArray())
            zip.closeEntry()
            val dir = samplesDir(id)
            for (name in project.usedSamples().sorted()) {
                val file = File(dir, name)
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(ENTRY_SAMPLES + name))
                file.inputStream().use { it.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a file written by [exportTo] and adds it to the list as a new project.
     *
     * @param rename lets the caller avoid two projects with the same name.
     * @return the id of the new project.
     */
    @Throws(IOException::class)
    fun importFrom(input: InputStream, rename: (String) -> String = { it }): String {
        val id = newId()
        val dir = dir(id)
        try {
            val samples = File(dir, "samples").also { it.mkdirs() }
            var project: Project? = null
            var bytes = 0L
            var entries = 0
            val buffer = ByteArray(1 shl 16)
            ZipInputStream(BufferedInputStream(input, 1 shl 16)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_FILE_ENTRIES) throw IOException("Too many files in the archive")
                    val name = entry.name
                    if (entry.isDirectory) continue
                    if (name == ENTRY_PROJECT) {
                        val text = ByteArrayOutputStream()
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            if (text.size() + n > MAX_PROJECT_BYTES) throw IOException("The project is too large")
                            text.write(buffer, 0, n)
                        }
                        project = json.decodeFromString(Project.serializer(), text.toString("UTF-8")).sanitized()
                    } else if (name.startsWith(ENTRY_SAMPLES)) {
                        val plain = name.substring(ENTRY_SAMPLES.length)
                        // Plain file names only: an archive must not be able to write outside its folder.
                        if (plain.isEmpty() || plain == "." || plain == ".." || plain.contains('/') || plain.contains('\\')) continue
                        File(samples, plain).outputStream().use { out ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                bytes += n
                                if (bytes > MAX_FILE_BYTES) throw IOException("The project is too large")
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }
            val loaded = (project ?: throw IOException("Not an MPS project")).let { it.copy(name = rename(it.name)) }
            save(id, loaded)
            if (!projectFile(id).isFile) throw IOException("The project could not be saved")
            pruneSamples(id, loaded.usedSamples())
            return id
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw if (e is IOException) e else IOException(e)
        }
    }

    // ---------------------------------------------------------------- samples

    /** Stores audio in the project and returns the file name to reference it by. */
    fun saveSample(id: String, baseName: String, data: SampleData): String {
        val safe = safeSampleName(baseName)
        val dir = samplesDir(id)
        var name = "$safe.wav"
        var n = 2
        while (File(dir, name).exists()) name = "$safe $n.wav".also { n++ }
        Wav.write(File(dir, name), data)
        return name
    }

    /**
     * Copies a sound of the library into the project and returns the name to reference it by.
     * A sound that is already there (same title, same content) is not stored twice.
     */
    @Throws(IOException::class)
    fun adoptSample(id: String, source: File): String {
        val dir = samplesDir(id)
        SoundLibrary.findSame(dir, source)?.let { return it }
        val safe = safeSampleName(source.name.substringBeforeLast('.'))
        var name = "$safe.wav"
        var n = 2
        while (File(dir, name).exists()) name = "$safe $n.wav".also { n++ }
        source.copyTo(File(dir, name), overwrite = true)
        return name
    }

    fun loadSample(id: String, name: String): SampleData? = try {
        val f = File(samplesDir(id), name)
        if (f.exists()) Wav.read(f) else null
    } catch (e: Exception) {
        Log.w(TAG, "Could not read sample $name", e)
        null
    }

    /** Deletes sample files that the project no longer references. */
    fun pruneSamples(id: String, used: Set<String>) {
        val files = samplesDir(id).listFiles() ?: return
        for (f in files) if (f.name !in used) f.delete()
    }

    companion object {
        private const val TAG = "MPS-Store"

        /** A title turned into something every file system accepts as a file name. */
        fun safeSampleName(title: String): String =
            title.replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").trim().take(40).ifEmpty { "audio" }

        /** Extension of exported project files. */
        const val FILE_EXTENSION = "mps"
        private const val FILE_FORMAT = 1
        private const val ENTRY_INFO = "mps.json"
        private const val ENTRY_PROJECT = "project.json"
        private const val ENTRY_SAMPLES = "samples/"
        private const val MAX_FILE_ENTRIES = 4000
        private const val MAX_PROJECT_BYTES = 32 shl 20
        private const val MAX_FILE_BYTES = 2L shl 30
    }
}
