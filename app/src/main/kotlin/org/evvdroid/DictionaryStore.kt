package org.evvdroid

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Immutable, content-addressed external dictionary snapshots.
 *
 * Source reads and publication run on one dedicated worker. A snapshot is
 * staged, fsynced, validated, and atomically renamed before its preference is
 * committed. Leases prevent a snapshot being pruned while native ECI reads it.
 */
internal object DictionaryStore {
    private const val PREF_SNAPSHOT = "evv_dictionary_snapshot_"
    private const val PREF_STATE = "evv_dictionary_state_"
    private val guard = Any()
    private val leases = HashMap<String, Int>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "evvdroid-dictionary-store").apply { isDaemon = true }
    }

    internal class Lease internal constructor(
        private val context: Context,
        val id: String,
        val directory: File?,
        val volumes: Map<Int, String>
    ) : AutoCloseable {
        @Volatile private var closed = false

        override fun close() {
            synchronized(guard) {
                if (closed) return
                closed = true
                directory?.absolutePath?.let { path ->
                    val count = leases[path] ?: 0
                    if (count <= 1) leases.remove(path) else leases[path] = count - 1
                }
            }
            worker.execute { prune(context) }
        }
    }

    /** The calling TTS setup thread waits; all filesystem work stays elsewhere. */
    fun acquire(context: Context, settings: Settings, language: Int): Lease {
        val app = context.applicationContext
        return worker.submit<Lease> { acquireOnWorker(app, settings, language) }.get()
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    private fun root(context: Context): File =
        File(context.filesDir, "dictionary-snapshots")

    private fun acquireOnWorker(context: Context, settings: Settings, language: Int): Lease {
        val selected = LinkedHashMap<Int, File>()
        for ((volume, path) in settings.dictionaryPaths().toSortedMap()) {
            if (!settings.useDictionary(volume)) continue
            if (volume == Eci.DICT_ABBREVIATION && !settings.abbreviations) continue
            if (settings.dictionaryLanguageResolved(volume, EvvEngine.available) != language) continue
            val source = File(path).canonicalFile
            if (!source.isFile) throw IOException("Selected dictionary is unavailable: ${source.name}")
            if (source.length() > DictionaryData.LIMIT) throw IOException("Dictionary exceeds 8 MiB: ${source.name}")
            selected[volume] = source
        }

        val state = sourceState(selected)
        val prefs = preferences(context)
        val langKey = language.toString(16)
        val snapshotKey = PREF_SNAPSHOT + langKey
        val stateKey = PREF_STATE + langKey
        val oldPath = prefs.getString(snapshotKey, null)
        if (state == prefs.getString(stateKey, null) && completeSnapshot(context, oldPath)) {
            return leaseFor(context, language, oldPath)
        }

        val before = sourceState(selected)
        val data = LinkedHashMap<Int, ByteArray>()
        for ((volume, source) in selected) {
            data[volume] = DictionaryData.encodeSource(source, language)
        }
        if (before != sourceState(selected)) {
            throw IOException("Dictionary source changed during snapshot creation")
        }

        if (data.isEmpty()) {
            synchronized(guard) {
                if (!prefs.edit().remove(snapshotKey).putString(stateKey, state).commit()) {
                    throw IOException("Could not publish the empty dictionary selection")
                }
            }
            prune(context)
            return Lease(context, "builtin-${langKey}", null, emptyMap())
        }

        val id = DictionaryData.snapshotHash(data)
        val base = root(context)
        if (!base.isDirectory && !base.mkdirs()) throw IOException("Snapshot directory unavailable")
        val target = File(base, id)
        if (!completeSnapshot(context, target.absolutePath)) {
            val stage = File(base, ".stage-${langKey}-${Thread.currentThread().id}")
            erase(stage)
            if (!stage.mkdir()) throw IOException("Cannot create dictionary staging directory")
            try {
                for ((volume, bytes) in data) write(File(stage, DictionaryData.volumeName(volume)), bytes)
                write(File(stage, "ready"), id.toByteArray(Charsets.US_ASCII))
                if (target.exists()) erase(target)
                if (!stage.renameTo(target) && !completeSnapshot(context, target.absolutePath)) {
                    throw IOException("Atomic dictionary publication failed")
                }
            } finally {
                erase(stage)
            }
        }
        if (!completeSnapshot(context, target.absolutePath)) {
            throw IOException("Published dictionary snapshot failed validation")
        }

        synchronized(guard) {
            if (!prefs.edit()
                    .putString(snapshotKey, target.absolutePath)
                    .putString(stateKey, state)
                    .commit()) {
                throw IOException("Could not activate dictionary snapshot")
            }
            leases[target.absolutePath] = (leases[target.absolutePath] ?: 0) + 1
        }
        prune(context)
        return Lease(
            context, id, target,
            data.keys.associateWith { File(target, DictionaryData.volumeName(it)).absolutePath }
        )
    }

    private fun leaseFor(context: Context, language: Int, path: String?): Lease {
        if (path == null) return Lease(context, "builtin-${language.toString(16)}", null, emptyMap())
        val folder = File(path).canonicalFile
        if (folder.parentFile != root(context).canonicalFile ||
            !folder.name.matches(Regex("[a-f0-9]{64}")) ||
            !completeSnapshot(context, folder.absolutePath)) {
            throw IOException("Invalid active dictionary snapshot")
        }
        val files = LinkedHashMap<Int, String>()
        for (volume in Eci.DICT_VOLUMES) {
            val file = File(folder, DictionaryData.volumeName(volume))
            if (file.isFile) files[volume] = file.absolutePath
        }
        synchronized(guard) {
            leases[folder.absolutePath] = (leases[folder.absolutePath] ?: 0) + 1
        }
        return Lease(context, folder.name, folder, files)
    }

    private fun sourceState(sources: Map<Int, File>): String =
        sources.entries.joinToString("|") { (volume, file) ->
            "$volume:${DictionaryData.sourceFingerprint(file)}"
        }

    private fun completeSnapshot(context: Context, path: String?): Boolean {
        if (path == null) return false
        return try {
            val folder = File(path).canonicalFile
            if (folder.parentFile != root(context).canonicalFile ||
                !folder.name.matches(Regex("[a-f0-9]{64}"))) return false
            val marker = File(folder, "ready")
            if (!marker.isFile || marker.readText(Charsets.US_ASCII) != folder.name) return false
            val data = LinkedHashMap<Int, ByteArray>()
            for (volume in Eci.DICT_VOLUMES) {
                val file = File(folder, DictionaryData.volumeName(volume))
                if (!file.exists()) continue
                if (!file.isFile || file.canonicalFile.parentFile != folder) return false
                data[volume] = read(file)
            }
            data.isNotEmpty() && DictionaryData.snapshotHash(data) == folder.name
        } catch (_: Exception) {
            false
        }
    }

    private fun read(file: File): ByteArray {
        if (file.length() > DictionaryData.LIMIT) throw IOException("Snapshot volume exceeds 8 MiB")
        FileInputStream(file).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() > DictionaryData.LIMIT - count) throw IOException("Snapshot volume exceeds 8 MiB")
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }

    private fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun erase(file: File) {
        file.listFiles()?.forEach(::erase)
        file.delete()
    }

    private fun prune(context: Context) {
        val active = preferences(context).all.entries
            .filter { it.key.startsWith(PREF_SNAPSHOT) }
            .mapNotNull { it.value as? String }
            .toSet()
        val folders = root(context).listFiles() ?: return
        val remove = ArrayList<File>()
        synchronized(guard) {
            for (folder in folders) {
                if (!folder.isDirectory) continue
                val path = folder.absolutePath
                if (path !in active && (leases[path] ?: 0) == 0) remove.add(folder)
            }
            remove.forEach { leases.remove(it.absolutePath) }
        }
        remove.forEach(::erase)
    }
}
