package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.PluginManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

class VerifiedPluginPackage internal constructor(
    val hash: String,
    val manifest: PluginManifest,
    val source: String,
    private val resources: Map<String, ByteArray>,
) {
    fun resource(path: String): ByteArray = requireNotNull(resources[path]) { "Unknown package resource" }.copyOf()
}

/** Reads bounded bytes only. Archive paths never become filesystem paths or executable native files. */
object PluginArchive {
    const val MAX_ARCHIVE_BYTES = 4 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 8 * 1024 * 1024
    private const val MAX_FILES = 128
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun decode(bytes: ByteArray, expectedHash: String? = null): VerifiedPluginPackage {
        require(bytes.size in 22..MAX_ARCHIVE_BYTES) { "Invalid plugin archive size" }
        val hash = hash(bytes)
        require(expectedHash == null || hash == expectedHash) { "Plugin hash mismatch" }
        try {
            val names = centralNames(bytes)
            val files = linkedMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var total = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(files.size < MAX_FILES && !entry.isDirectory) {
                        "Only declared regular files are supported"
                    }
                    PluginContract.path(entry.name)
                    require(entry.name in names && entry.name !in files) { "Ambiguous ZIP entry" }
                    val content = readBounded(zip, MAX_EXPANDED_BYTES - total)
                    total += content.size
                    files[entry.name] = content
                    zip.closeEntry()
                }
            }
            require(names == files.keys) { "ZIP directory disagrees with file contents" }
            val manifest = PluginContract.manifest(utf8(requireNotNull(files["manifest.json"]) { "Missing manifest" }))
            val declared = setOf("manifest.json", manifest.entry) + manifest.resources
            require(files.keys == declared) { "Package resources do not match manifest" }
            val source = utf8(files.getValue(manifest.entry))
            require(source.length <= 768 * 1024 && source.isNotBlank()) { "Invalid plugin source size" }
            return VerifiedPluginPackage(hash, manifest, source, files.filterKeys { it in manifest.resources })
        } catch (error: Exception) {
            if (error is IllegalArgumentException) throw error
            throw IllegalArgumentException("Invalid plugin archive", error)
        }
    }

    fun requireHash(hash: String) {
        require(hashPattern.matches(hash)) { "Invalid package hash" }
    }

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "Package exceeds byte limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    /** ZIP64, split archives, links, encryption, directory entries and ambiguous spellings are rejected. */
    private fun centralNames(bytes: ByteArray): Set<String> {
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun short(at: Int): Int = data.getShort(at).toInt() and 0xffff
        val end = bytes.size - 22
        require(data.getInt(end) == 0x06054b50 && short(end + 20) == 0) { "ZIP must have an unambiguous end record" }
        require(short(end + 4) == 0 && short(end + 6) == 0 && short(end + 8) == short(end + 10))
        val count = short(end + 10)
        require(count in 1..MAX_FILES)
        val start = data.getInt(end + 16)
        val length = data.getInt(end + 12)
        require(start >= 0 && length >= 0 && start.toLong() + length == end.toLong())
        val names = linkedSetOf<String>()
        val folded = hashSetOf<String>()
        var offset = start
        repeat(count) {
            require(offset + 46 <= end && data.getInt(offset) == 0x02014b50)
            val flags = short(offset + 8)
            val method = short(offset + 10)
            require(flags and 1 == 0 && method in setOf(0, 8)) { "Unsupported ZIP encoding" }
            require(short(offset + 34) == 0)
            val mode = data.getInt(offset + 38) ushr 16
            require(mode and 0xf000 in setOf(0, 0x8000)) { "ZIP special files are unsupported" }
            val nameSize = short(offset + 28)
            val next = offset + 46 + nameSize + short(offset + 30) + short(offset + 32)
            require(next <= end)
            val name = utf8(bytes.copyOfRange(offset + 46, offset + 46 + nameSize))
            PluginContract.path(name)
            require(names.add(name) && folded.add(name.lowercase(Locale.ROOT))) { "Duplicate ZIP path" }
            val local = data.getInt(offset + 42)
            require(local >= 0 && local.toLong() + 30 <= start && data.getInt(local) == 0x04034b50)
            require(short(local + 6) == flags && short(local + 8) == method && short(local + 26) == nameSize)
            require(local.toLong() + 30 + nameSize + short(local + 28) <= start)
            require(bytes.copyOfRange(local + 30, local + 30 + nameSize).contentEquals(name.toByteArray()))
            offset = next
        }
        require(offset == end)
        return names
    }
}
