package jxpix.proger.mps.data

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
class ProjectStore(context: Context) {

    private val root = File(context.filesDir, "projects")
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
        val id = UUID.randomUUID().toString().substring(0, 12)
        save(id, project)
        return id
    }

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
        val newId = UUID.randomUUID().toString().substring(0, 12)
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

    // ---------------------------------------------------------------- samples

    /** Stores audio in the project and returns the file name to reference it by. */
    fun saveSample(id: String, baseName: String, data: SampleData): String {
        val safe = baseName.replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").trim().take(40).ifEmpty { "audio" }
        val dir = samplesDir(id)
        var name = "$safe.wav"
        var n = 2
        while (File(dir, name).exists()) name = "$safe $n.wav".also { n++ }
        Wav.write(File(dir, name), data)
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

    private companion object {
        const val TAG = "MPS-Store"
    }
}
