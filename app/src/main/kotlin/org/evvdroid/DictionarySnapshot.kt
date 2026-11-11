package org.evvdroid

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * One immutable, language-specific publication of all three ECI volumes.
 *
 * The identity is made from SHA-256 of each source file, not its path, size,
 * or timestamp. A source changed while the snapshot is being made is refused.
 * The three output files and manifest are written in a private staging
 * directory and the directory is renamed into place only when all are ready.
 *
 * The service owns one reference. Every utterance takes a lease; replacing a
 * snapshot retires the old owner reference, and its directory is removed only
 * after the last utterance lease has gone away.
 */
private object DictionarySnapshotDirectories {
    private val references = ConcurrentHashMap<String, AtomicInteger>()

    fun retain(directory: File) {
        references.compute(directory.absolutePath) { _, count ->
            (count ?: AtomicInteger()).also { it.incrementAndGet() }
        }
    }

    fun release(directory: File) {
        val key = directory.absolutePath
        var delete = false
        references.compute(key) { _, count ->
            if (count == null || count.decrementAndGet() <= 0) {
                delete = count != null
                null
            } else {
                count
            }
        }
        if (delete) directory.deleteRecursively()
    }

    fun isInUse(directory: File): Boolean =
        (references[directory.absolutePath]?.get() ?: 0) > 0
}

internal class DictionarySnapshot private constructor(
    val language: Int,
    val identity: String,
    val paths: Array<String?>,
    private val directory: File?
) {
    private val references = AtomicInteger(1)
    private val retired = AtomicBoolean(false)

    init { directory?.let(DictionarySnapshotDirectories::retain) }

    fun acquire(): Lease {
        while (true) {
            val current = references.get()
            check(current > 0 && !retired.get()) { "dictionary snapshot is retired" }
            if (references.compareAndSet(current, current + 1)) return Lease(this)
        }
    }

    fun retire() {
        if (retired.compareAndSet(false, true)) release()
    }

    private fun release() {
        if (references.decrementAndGet() == 0) directory?.let(DictionarySnapshotDirectories::release)
    }

    class Lease internal constructor(private val snapshot: DictionarySnapshot) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) snapshot.release()
        }
    }

    companion object {
        private const val MANIFEST = "snapshot.manifest"
        private const val VERSION = "evvdroid-dictionary-snapshot-v1"

        /**
         * Returns [current] unchanged when its content identity still matches.
         * The caller owns the returned snapshot and must retire it on failure
         * or when a later snapshot replaces it.
         */
        fun prepare(
            root: File,
            language: Int,
            sources: Map<Int, File>,
            current: DictionarySnapshot?
        ): DictionarySnapshot {
            val digests = LinkedHashMap<Int, String>()
            for (volume in Eci.DICT_VOLUMES.sorted()) {
                val source = sources[volume] ?: continue
                if (!source.isFile || !source.canRead()) {
                    throw java.io.IOException("dictionary volume $volume is not readable")
                }
                digests[volume] = sha256(source)
            }

            val identityText = buildString {
                append(VERSION).append('\n').append(language).append('\n')
                for ((volume, digest) in digests) append(volume).append(':').append(digest).append('\n')
            }
            val identity = sha256(identityText.toByteArray(Charsets.UTF_8))
            if (current != null && current.language == language && current.identity == identity) {
                return current
            }

            if (digests.isEmpty()) {
                return DictionarySnapshot(language, identity, arrayOfNulls(Eci.DICT_VOLUMES.size), null)
            }

            root.mkdirs()
            val finalDir = File(root, "snapshot-${language.toString(16)}-$identity")
            val expectedManifest = StringBuilder()
                .append(VERSION).append('\n')
                .append(language).append('\n')
                .append(identity).append('\n')

            if (validSnapshot(finalDir, digests, expectedManifest.toString())) {
                return makeSnapshot(language, identity, finalDir)
            }

            val staging = File(root, ".snapshot-${UUID.randomUUID()}.tmp")
            if (!staging.mkdirs()) throw java.io.IOException("cannot create dictionary snapshot staging directory")
            try {
                val outputDigests = LinkedHashMap<Int, String>()
                for (volume in Eci.DICT_VOLUMES.sorted()) {
                    val source = sources[volume] ?: continue
                    val before = digests[volume] ?: continue
                    val destination = File(staging, volumeFile(volume))
                    if (Dictionaries.isBinary(source)) {
                        source.copyTo(destination, overwrite = true)
                    } else {
                        Dictionaries.writeEngineFile(source, language, destination)
                    }
                    val after = sha256(source)
                    if (before != after) {
                        throw java.io.IOException("dictionary volume $volume changed while its snapshot was being built")
                    }
                    outputDigests[volume] = sha256(destination)
                }

                val manifest = buildString {
                    append(expectedManifest)
                    for ((volume, digest) in digests) {
                        append(volume).append(':').append(digest).append(':')
                            .append(outputDigests[volume]).append('\n')
                    }
                }
                val manifestFile = File(staging, MANIFEST)
                FileOutputStream(manifestFile).use { stream ->
                    stream.write(manifest.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }

                if (finalDir.exists()) {
                    if (validSnapshot(finalDir, digests, expectedManifest.toString())) {
                        staging.deleteRecursively()
                    } else {
                        if (DictionarySnapshotDirectories.isInUse(finalDir)) {
                            throw java.io.IOException("an active lease holds an invalid dictionary snapshot")
                        }\n                        if (!finalDir.deleteRecursively()) {
                            throw java.io.IOException("cannot replace an invalid dictionary snapshot")
                        }
                        if (!staging.renameTo(finalDir)) {
                            throw java.io.IOException("cannot publish dictionary snapshot")
                        }
                    }
                } else if (!staging.renameTo(finalDir)) {
                    throw java.io.IOException("cannot publish dictionary snapshot")
                }
                if (!validSnapshot(finalDir, digests, expectedManifest.toString())) {
                    throw java.io.IOException("published dictionary snapshot failed validation")
                }
                return makeSnapshot(language, identity, finalDir)
            } catch (failed: Exception) {
                staging.deleteRecursively()
                throw failed
            }
        }

        private fun makeSnapshot(language: Int, identity: String, directory: File): DictionarySnapshot {
            val paths = arrayOfNulls<String>(Eci.DICT_VOLUMES.size)
            for (volume in Eci.DICT_VOLUMES) {
                val file = File(directory, volumeFile(volume))
                if (file.isFile) paths[volume] = file.absolutePath
            }
            return DictionarySnapshot(language, identity, paths, directory)
        }

        private fun validSnapshot(
            directory: File,
            digests: Map<Int, String>,
            header: String
        ): Boolean {
            if (!directory.isDirectory) return false
            val manifest = File(directory, MANIFEST)
            if (!manifest.isFile) return false
            val text = runCatching { manifest.readText(Charsets.UTF_8) }.getOrNull() ?: return false
            if (!text.startsWith(header)) return false
            for ((volume, sourceDigest) in digests) {
                val line = text.lineSequence().firstOrNull { it.startsWith("$volume:$sourceDigest:") }
                    ?: return false
                val expectedOutput = line.substringAfterLast(':')
                val file = File(directory, volumeFile(volume))
                if (!file.isFile || sha256(file) != expectedOutput) return false
            }
            return true
        }

        private fun volumeFile(volume: Int): String = when (volume) {
            Eci.DICT_MAIN -> "main.dic"
            Eci.DICT_ROOT -> "root.dic"
            Eci.DICT_ABBREVIATION -> "abbr.dic"
            else -> throw IllegalArgumentException("unknown dictionary volume $volume")
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
