package jxpix.proger.mps.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A sound the user dialled in and saved under a name: the positions of all knobs of one device. */
@Serializable
data class UserPreset(val name: String, val params: Map<String, Float>)

/**
 * The user's own presets, for every kind of instrument and effect. They belong to the app,
 * not to a project, so a sound made in one track is there in the next one.
 *
 * One small JSON file per kind of device in files/presets.
 */
class PresetStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, "presets"))

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(UserPreset.serializer())

    private fun file(type: String) = File(dir, type.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Presets of every kind of device, by device type id. */
    fun loadAll(): Map<String, List<UserPreset>> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        val out = HashMap<String, List<UserPreset>>()
        for (f in files) {
            val list = read(f)
            if (list.isNotEmpty()) out[f.name.removeSuffix(".json")] = list
        }
        return out
    }

    fun list(type: String): List<UserPreset> = read(file(type))

    private fun read(f: File): List<UserPreset> = try {
        if (f.isFile) sorted(json.decodeFromString(serializer, f.readText())) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun sorted(list: List<UserPreset>) = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /** Adds a preset, or replaces the one with the same name. Returns the new list for [type]. */
    @Synchronized
    fun save(type: String, preset: UserPreset): List<UserPreset> {
        val name = preset.name.trim().take(MAX_NAME)
        if (name.isEmpty()) return list(type)
        val list = sorted(list(type).filter { !it.name.equals(name, ignoreCase = true) } + preset.copy(name = name))
        write(type, list)
        return list
    }

    @Synchronized
    fun delete(type: String, name: String): List<UserPreset> {
        val list = list(type).filter { it.name != name }
        write(type, list)
        return list
    }

    private fun write(type: String, list: List<UserPreset>) {
        val f = file(type)
        if (list.isEmpty()) {
            f.delete()
            return
        }
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, list))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    companion object {
        const val MAX_NAME = 32
    }
}
