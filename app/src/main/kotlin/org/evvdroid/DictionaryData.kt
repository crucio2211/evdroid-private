package org.evvdroid

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Builds one normalized, engine-native volume from an imported dictionary. */
internal object DictionaryData {
    const val LIMIT = 8 * 1024 * 1024
    private const val MAX_KEY_BYTES = 128
    private const val MAX_SAY_BYTES = 512

    fun volumeName(volume: Int): String = when (volume) {
        Eci.DICT_MAIN -> "main.dic"
        Eci.DICT_ROOT -> "root.dic"
        Eci.DICT_ABBREVIATION -> "abbr.dic"
        else -> throw IllegalArgumentException("Unsupported dictionary volume: $volume")
    }

    fun encodeSource(file: File, language: Int): ByteArray {
        if (!file.isFile) throw IOException("Dictionary source is missing: ${file.name}")
        if (file.length() > LIMIT) throw IOException("Dictionary exceeds 8 MiB: ${file.name}")
        if (Dictionaries.isBinary(file)) {
            val bytes = file.readBytes()
            if (bytes.size > LIMIT) throw IOException("Dictionary exceeds 8 MiB: ${file.name}")
            return bytes
        }

        // Parsing and normalization happen once, on DictionaryStore's worker.
        Dictionaries.invalidate(file)
        val entries = Dictionaries.parse(file)
        if (entries.isEmpty()) throw IOException("No usable entries in ${file.name}")

        val out = ByteArrayOutputStream()
        for ((key, say) in entries.values.sortedBy { it.first }) {
            val keyBytes = EngineText.encode(language, key)
            val sayBytes = EngineText.encode(language, say)
            if (keyBytes.isEmpty() || keyBytes.size > MAX_KEY_BYTES ||
                sayBytes.isEmpty() || sayBytes.size > MAX_SAY_BYTES) continue

            fun invalid(bytes: ByteArray): Boolean = bytes.any {
                it == 0.toByte() || it == '\t'.code.toByte() ||
                    it == '\r'.code.toByte() || it == '\n'.code.toByte()
            }
            if (invalid(keyBytes) || invalid(sayBytes)) continue
            if (out.size() > LIMIT - keyBytes.size - sayBytes.size - 2) {
                throw IOException("Normalized dictionary exceeds 8 MiB: ${file.name}")
            }
            out.write(keyBytes)
            out.write('\t'.code)
            out.write(sayBytes)
            out.write('\n'.code)
        }

        val normalized = out.toByteArray()
        if (normalized.isEmpty()) throw IOException("No entries fit the engine format: ${file.name}")
        return normalized
    }

    /** Hash source bytes, not metadata: equal-size/equal-timestamp edits must reload. */
    fun sourceFingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun snapshotHash(volumes: Map<Int, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((volume, bytes) in volumes.toSortedMap()) {
            digest.update(volumeName(volume).toByteArray(StandardCharsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
