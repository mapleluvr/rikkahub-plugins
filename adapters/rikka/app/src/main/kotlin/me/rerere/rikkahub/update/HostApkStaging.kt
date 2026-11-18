package me.rerere.rikkahub.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class HostApkIdentity(val sha256: String, val sizeBytes: Long, val versionCode: Long)

internal data class HostPreparedApk(val file: File, internal val ownerId: String)

@Serializable
internal data class HostInstallTicket(val fileId: String, val requestId: String)

/** Owns bounded APK material through download, foreground approval and installer handoff. */
internal class HostApkStaging(
    private val directory: File,
    private val installedVersionCode: () -> Long = { 0 },
    private val legacyDirectory: File? = null,
    private val obsoleteLegacyFile: (File) -> Boolean = { false },
) {
    private val lock = locks.computeIfAbsent(directory.canonicalPath) { Mutex() }

    suspend fun prepare(
        download: suspend (File) -> Unit,
        verify: suspend (File) -> Unit,
        identity: HostApkIdentity? = null,
    ): HostPreparedApk {
        identity?.let {
            require(it.sha256.matches(SHA256) && it.sizeBytes in 1..MAX_HOST_APK_BYTES && it.versionCode > 0) {
                "Host APK identity is invalid"
            }
        }
        // This outer owner includes prompt cancellation while the IO dispatcher returns.
        var prepared: HostPreparedApk? = null
        try {
            return withContext(Dispatchers.IO) {
                val lease = lock.withLock {
                    recoverLocked()
                    val retained = identity?.let { requested ->
                        records().firstOrNull {
                            it.verified && it.identity == requested
                        }
                    }
                    val owner = Owner(newId(), processId)
                    val record = if (retained != null) {
                        check(retained.owners.size < MAX_REFERENCES) { "Host installation requires verification" }
                        retained.copy(owners = retained.owners + owner)
                    } else {
                        check(storageSlots() < MAX_APKS) { "Host download storage is busy" }
                        Record(id = newId(), identity = identity, owners = listOf(owner))
                    }
                    HostPreparedApk(apk(record.id), owner.id).also {
                        prepared = it
                        save(record)
                        if (!record.verified) {
                            check(it.file.createNewFile()) { "Host download storage is unavailable" }
                        }
                    }
                }
                val alreadyVerified = lock.withLock { read(recordId(lease.file)).verified }
                if (!alreadyVerified) download(lease.file)
                currentCoroutineContext().ensureActive()
                verify(lease.file)
                currentCoroutineContext().ensureActive()
                val digest = digest(lease.file)
                identity?.let {
                    check(lease.file.length() == it.sizeBytes && digest == it.sha256) { "Host APK identity mismatch" }
                }
                lock.withLock {
                    val record = ownedRecord(lease)
                    save(record.copy(verified = true, verifiedSha256 = digest))
                }
                currentCoroutineContext().ensureActive()
                lease
            }
        } catch (error: Throwable) {
            prepared?.let { lease ->
                try {
                    withContext(NonCancellable) { discard(lease) }
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    suspend fun discard(prepared: HostPreparedApk) = transaction {
        val id = recordId(prepared.file)
        if (!metadata(id).exists()) return@transaction
        val record = ownedRecord(prepared)
        replaceOrDelete(record.copy(owners = record.owners.filterNot { it.id == prepared.ownerId }))
    }

    /** Persists an internal bridge request; only its originating process may dispatch it. */
    suspend fun reserveInstall(prepared: HostPreparedApk): HostInstallTicket {
        var reserved: HostInstallTicket? = null
        try {
            return transaction {
                val record = ownedRecord(prepared)
                check(record.verified && record.identity != null) { "Host APK has not been verified" }
                check(record.handoffs.size < MAX_REFERENCES) { "Host installation requires verification" }
                val handoff = Handoff(newId(), processId)
                HostInstallTicket(record.id, handoff.id).also {
                    reserved = it
                    save(record.copy(handoffs = record.handoffs + handoff))
                }
            }
        } catch (error: Throwable) {
            reserved?.let { request ->
                try {
                    withContext(NonCancellable) { refuseUnsentInstall(request) }
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    /** Commit before invoking the system. A lost dispatch acknowledgment retains the material. */
    suspend fun authorizeInstaller(ticket: HostInstallTicket): File = transaction {
        val record = read(ticket.fileId)
        val handoff = record.handoffs.singleOrNull { it.id == ticket.requestId }
        check(handoff != null && handoff.processId == processId && !handoff.granted) {
            "Host installation requires verification"
        }
        val identity = requireNotNull(record.identity) { "Host APK identity is unavailable" }
        check(installedVersionCode() < identity.versionCode) { "This update is no longer available" }
        val file = apk(record.id)
        check(file.isFile && file.length() == identity.sizeBytes && digest(file) == identity.sha256) {
            "Host APK identity mismatch"
        }
        save(record.copy(handoffs = record.handoffs.map {
            if (it.id == handoff.id) it.copy(granted = true) else it
        }))
        file
    }

    /** The caller has proved that its system-launch boundary was never crossed. */
    suspend fun refuseUnsentInstall(ticket: HostInstallTicket) = transaction {
        if (!metadata(ticket.fileId).exists()) return@transaction
        val record = read(ticket.fileId)
        val handoff = record.handoffs.singleOrNull { it.id == ticket.requestId } ?: return@transaction
        check(handoff.processId == processId) { "Host installation requires verification" }
        replaceOrDelete(record.copy(handoffs = record.handoffs.filterNot { it.id == ticket.requestId }))
    }

    /** A result belongs to this request even if another update is now shown in the foreground. */
    suspend fun finishInstall(ticket: HostInstallTicket) = transaction {
        if (!metadata(ticket.fileId).exists()) return@transaction
        val record = read(ticket.fileId)
        if (record.handoffs.none { it.id == ticket.requestId }) return@transaction
        replaceOrDelete(record.copy(
            owners = record.owners.filter { it.processId == processId },
            handoffs = record.handoffs.filterNot { it.id == ticket.requestId },
        ))
    }

    suspend fun hasInstall(ticket: HostInstallTicket): Boolean = transaction {
        metadata(ticket.fileId).exists() && read(ticket.fileId).handoffs.any { it.id == ticket.requestId }
    }

    suspend fun recover() = transaction { recoverLocked() }

    private suspend fun <T> transaction(action: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { action() }
    }

    private fun recoverLocked() {
        check(directory.mkdirs() || directory.isDirectory) { "Host download storage is unavailable" }
        val installed = installedVersionCode()
        for (record in records()) {
            if (record.identity != null && record.identity.versionCode <= installed) {
                delete(record)
                continue
            }
            // Old bridge requests cannot dispatch: authorizeInstaller checks the process identity.
            val current = record.copy(
                owners = record.owners.filter { it.processId == processId },
                handoffs = record.handoffs.filter { it.granted || it.processId == processId },
            )
            if (current.owners.isEmpty() && current.handoffs.isEmpty()) {
                delete(current)
            } else if (current != record) {
                save(current)
            }
        }
        files(directory).filter { it.name.matches(TEMP_NAME) }.forEach(::deleteFile)
        for (root in listOfNotNull(directory, legacyDirectory).distinctBy { it.canonicalPath }) {
            files(root).filter { it.name.startsWith("host-update-") && it.extension == "apk" }.forEach { file ->
                val tracked = root.canonicalFile == directory.canonicalFile &&
                    file.name.matches(APK_NAME) && metadata(recordId(file)).exists()
                // Legacy grants have no receipt. Installed-version evidence permits reclamation.
                if (!tracked && obsoleteLegacyFile(file)) deleteFile(file)
            }
        }
    }

    private fun storageSlots(): Int {
        val tracked = records().map { apk(it.id).canonicalPath }.toSet()
        val untracked = listOfNotNull(directory, legacyDirectory).distinctBy { it.canonicalPath }.flatMap(::files)
            .count { it.name.startsWith("host-update-") && it.extension == "apk" && it.canonicalPath !in tracked }
        return tracked.size + untracked
    }

    private fun ownedRecord(prepared: HostPreparedApk): Record {
        val record = read(recordId(prepared.file))
        check(record.owners.any { it.id == prepared.ownerId && it.processId == processId }) {
            "Host APK ownership has expired"
        }
        return record
    }

    private fun replaceOrDelete(record: Record) {
        if (record.owners.isEmpty() && record.handoffs.isEmpty()) delete(record) else save(record)
    }

    private fun records(): List<Record> = files(directory).filter { it.name.matches(METADATA_NAME) }
        .map { read(it.name.removePrefix("host-update-").removeSuffix(".lease")) }

    private fun read(id: String): Record {
        check(id.matches(ID)) { "Invalid host APK identity" }
        val file = metadata(id)
        check(file.isFile && file.length() <= MAX_METADATA_BYTES) { "Host download storage requires verification" }
        val record = json.decodeFromString<Record>(file.readText(Charsets.UTF_8))
        check(
            record.schemaVersion == 1 && record.id == id && record.owners.size <= MAX_REFERENCES &&
                record.handoffs.size <= MAX_REFERENCES && record.owners.map {
                    it.id
                }.distinct().size == record.owners.size &&
                record.handoffs.map { it.id }.distinct().size == record.handoffs.size &&
                record.owners.all { it.id.matches(ID) && it.processId.matches(ID) } &&
                record.handoffs.all { it.id.matches(ID) && it.processId.matches(ID) } &&
                (!record.verified || record.verifiedSha256?.matches(SHA256) == true) &&
                (record.handoffs.isEmpty() || record.verified)
        ) { "Host download storage requires verification" }
        record.identity?.let {
            check(it.sha256.matches(SHA256) && it.sizeBytes > 0 && it.versionCode > 0) {
                "Host APK identity is invalid"
            }
        }
        return record
    }

    private fun save(record: Record) {
        val temporary = File(directory, "host-update-${record.id}.lease.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.encodeToString(record).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                metadata(record.id).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            deleteFile(temporary)
        }
    }

    private fun delete(record: Record) {
        deleteFile(apk(record.id))
        deleteFile(metadata(record.id))
        deleteFile(File(directory, "host-update-${record.id}.lease.tmp"))
    }

    private fun deleteFile(file: File) {
        check(!file.exists() || file.delete()) { "Host download cleanup failed" }
    }

    private fun recordId(file: File): String {
        check(file.parentFile?.canonicalFile == directory.canonicalFile && file.name.matches(APK_NAME)) {
            "Invalid host APK location"
        }
        return file.name.removePrefix("host-update-").removeSuffix(".apk")
    }

    private fun apk(id: String): File {
        check(id.matches(ID)) { "Invalid host APK identity" }
        return File(directory, "host-update-$id.apk")
    }

    private fun metadata(id: String): File {
        check(id.matches(ID)) { "Invalid host APK identity" }
        return File(directory, "host-update-$id.lease")
    }

    private fun files(root: File): List<File> = if (root.exists()) {
        requireNotNull(root.listFiles()) { "Host download storage is unavailable" }.toList()
    } else {
        emptyList()
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private data class Owner(val id: String, val processId: String)

    @Serializable
    private data class Handoff(val id: String, val processId: String, val granted: Boolean = false)

    @Serializable
    private data class Record(
        val schemaVersion: Int = 1,
        val id: String,
        val identity: HostApkIdentity? = null,
        val verified: Boolean = false,
        val verifiedSha256: String? = null,
        val owners: List<Owner> = emptyList(),
        val handoffs: List<Handoff> = emptyList(),
    )

    private companion object {
        const val MAX_APKS = 3
        const val MAX_REFERENCES = 64
        const val MAX_METADATA_BYTES = 64 * 1024
        val ID = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val SHA256 = Regex("[a-f0-9]{64}")
        val APK_NAME = Regex("host-update-${ID.pattern}\\.apk")
        val METADATA_NAME = Regex("host-update-${ID.pattern}\\.lease")
        val TEMP_NAME = Regex("host-update-${ID.pattern}\\.lease\\.tmp")
        val processId = UUID.randomUUID().toString()
        val locks = ConcurrentHashMap<String, Mutex>()
        val json = Json { encodeDefaults = true }
        fun newId(): String = UUID.randomUUID().toString()
    }
}
