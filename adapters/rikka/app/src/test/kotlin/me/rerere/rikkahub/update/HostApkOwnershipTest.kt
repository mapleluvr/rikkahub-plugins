package me.rerere.rikkahub.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

class HostApkOwnershipTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = byteArrayOf(1, 2, 3, 4)

    @Test
    fun twoPreparationOwnersReuseValidatedMaterialAndReleaseIndependently() = runBlocking {
        val directory = temp.newFolder()
        val staging = HostApkStaging(directory)
        var downloads = 0
        var verifications = 0
        suspend fun acquire() = staging.prepare(
            download = {
                downloads++
                it.writeBytes(bytes)
            },
            verify = {
                verifications++
                assertArrayEquals(bytes, it.readBytes())
            },
            identity = identity(bytes),
        )
        val first = acquire()
        val second = acquire()
        assertEquals(first.file, second.file)
        assertEquals(1, downloads)
        assertEquals(2, verifications)
        staging.discard(first)
        assertTrue(second.file.isFile)
        staging.discard(second)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun pendingBridgeRequestKeepsItsFileUntilDefiniteRefusal() = runBlocking {
        val directory = temp.newFolder()
        val staging = HostApkStaging(directory)
        val prepared = prepare(staging)
        val ticket = staging.reserveInstall(prepared)
        staging.discard(prepared)
        staging.recover()
        assertTrue(prepared.file.isFile)
        assertTrue(staging.hasInstall(ticket))
        staging.refuseUnsentInstall(ticket)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun oldResultsCannotReleaseNewRequestsForTheSameMaterial() = runBlocking {
        val directory = temp.newFolder()
        val staging = HostApkStaging(directory)
        val first = prepare(staging)
        val old = staging.reserveInstall(first)
        staging.authorizeInstaller(old)
        staging.discard(first)
        val second = prepare(staging, download = { error("Validated material must be reused") })
        val current = staging.reserveInstall(second)
        staging.authorizeInstaller(current)
        staging.discard(second)
        staging.finishInstall(old)
        staging.finishInstall(old)
        assertTrue(staging.hasInstall(current))
        assertTrue(second.file.isFile)
        staging.finishInstall(current)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun grantedMaterialRemainsReadableDuringUnrelatedDownloadsAndRecovery() = runBlocking {
        val directory = temp.newFolder()
        val staging = HostApkStaging(directory)
        val first = prepare(staging)
        val ticket = staging.reserveInstall(first)
        staging.authorizeInstaller(ticket)
        staging.discard(first)
        val other = prepare(staging, payload = byteArrayOf(5, 6))
        HostApkStaging(directory).recover()
        first.file.inputStream().use { assertArrayEquals(bytes, it.readBytes()) }
        staging.discard(other)
        assertTrue(first.file.isFile)
        staging.finishInstall(ticket)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun anAuthorizedRequestRequiresReconciliationBeforeAnotherDispatch() = runBlocking {
        val staging = HostApkStaging(temp.newFolder())
        val prepared = prepare(staging)
        val ticket = staging.reserveInstall(prepared)
        staging.authorizeInstaller(ticket)
        assertTrue(runCatching { staging.authorizeInstaller(ticket) }.isFailure)
        assertTrue(prepared.file.isFile)
        staging.discard(prepared)
        staging.finishInstall(ticket)
    }

    @Test
    fun installedVersionRetiresItsOldMaterialAndMakesLateResultsIdempotent() = runBlocking {
        val directory = temp.newFolder()
        var installed = 1L
        val staging = HostApkStaging(directory, installedVersionCode = { installed })
        val prepared = prepare(staging)
        val ticket = staging.reserveInstall(prepared)
        staging.authorizeInstaller(ticket)
        staging.discard(prepared)
        installed = 2L
        staging.recover()
        staging.finishInstall(ticket)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun threeDistinctOutstandingMaterialsKeepTheOriginalBudget() = runBlocking {
        val staging = HostApkStaging(temp.newFolder())
        val tickets = (1..3).map { value ->
            val prepared = prepare(staging, payload = byteArrayOf(value.toByte()))
            val ticket = staging.reserveInstall(prepared)
            staging.authorizeInstaller(ticket)
            staging.discard(prepared)
            ticket
        }
        var downloaded = false
        val rejected = runCatching {
            prepare(staging, payload = byteArrayOf(4), download = { downloaded = true })
        }
        assertTrue(rejected.isFailure)
        assertFalse(downloaded)
        tickets.forEach { staging.finishInstall(it) }
    }

    @Test
    fun legacyUnknownGrantsCountTowardsCapacityAndConfirmedRetirementReleasesThem() = runBlocking {
        val directory = temp.newFolder()
        val legacy = temp.newFolder()
        repeat(3) { File(legacy, "host-update-legacy-$it.apk").writeBytes(bytes) }
        var retired = false
        val staging = HostApkStaging(directory, legacyDirectory = legacy, obsoleteLegacyFile = { retired })
        assertTrue(runCatching { prepare(staging) }.isFailure)
        assertEquals(3, legacy.listFiles()!!.size)
        retired = true
        val current = prepare(staging)
        assertTrue(legacy.listFiles()!!.isEmpty())
        staging.discard(current)
    }

    @Test
    fun malformedReceiptsBlockAdmissionAndPreserveTheirMaterial() = runBlocking {
        val directory = temp.newFolder()
        val staging = HostApkStaging(directory)
        val prepared = prepare(staging)
        val receipt = directory.listFiles()!!.single { it.extension == "lease" }
        receipt.writeText("{\"schemaVersion\":999}")
        assertTrue(runCatching { staging.recover() }.isFailure)
        var downloaded = false
        assertTrue(runCatching { prepare(staging, download = { downloaded = true }) }.isFailure)
        assertFalse(downloaded)
        assertTrue(prepared.file.isFile)
    }

    @Test
    fun failedDeletionPreservesTheReceiptAndReportsFailure() = runBlocking {
        val directory = temp.newFolder()
        val staging = HostApkStaging(directory)
        val prepared = prepare(staging)
        assertTrue(prepared.file.delete())
        assertTrue(prepared.file.mkdir())
        File(prepared.file, "occupied").writeText("owned fault injection")
        assertTrue(runCatching { staging.discard(prepared) }.isFailure)
        assertTrue(directory.listFiles()!!.any { it.extension == "lease" })
        assertTrue(prepared.file.exists())
    }

    @Test
    fun invalidOwnershipHandlesCannotDeleteOutsideTheStorageRoot() = runBlocking {
        val directory = temp.newFolder()
        val staging = HostApkStaging(directory)
        val prepared = prepare(staging)
        val outside = File(temp.newFolder(), prepared.file.name).apply { writeBytes(bytes) }
        assertTrue(runCatching { staging.discard(HostPreparedApk(outside, prepared.ownerId)) }.isFailure)
        assertArrayEquals(bytes, outside.readBytes())
        assertTrue(prepared.file.isFile)
        staging.discard(prepared)
    }

    @Test
    fun cancellationWhileReturningVerifiedMaterialDrainsItsPreparationOwner() = runBlocking {
        val directory = temp.newFolder()
        val staging = HostApkStaging(directory)
        cancelAtReturn { prepare(staging) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun cancellationWhileReturningABridgeRequestReleasesItsUnsentReservation() = runBlocking {
        val directory = temp.newFolder()
        val staging = HostApkStaging(directory)
        val prepared = prepare(staging)
        cancelAtReturn { staging.reserveInstall(prepared) }
        staging.discard(prepared)
        assertTrue("An unsent request survived cancellation", directory.listFiles()!!.isEmpty())
    }

    private suspend fun cancelAtReturn(action: suspend () -> Any) {
        val dispatcher = HeldReturnDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val call = scope.async(start = CoroutineStart.UNDISPATCHED) { action() }
        try {
            withTimeout(5000) { while (!dispatcher.hasReturn()) delay(5) }
            call.cancel()
            withTimeout(5000) {
                while (!call.isCompleted) {
                    dispatcher.resumePending()
                    delay(5)
                }
            }
            assertTrue(call.isCancelled)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun prepare(
        staging: HostApkStaging,
        payload: ByteArray = bytes,
        download: suspend (File) -> Unit = { it.writeBytes(payload) },
    ): HostPreparedApk = staging.prepare(
        download = download,
        verify = { assertArrayEquals(payload, it.readBytes()) },
        identity = identity(payload),
    )

    private fun identity(payload: ByteArray): HostApkIdentity = HostApkIdentity(
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        payload.size.toLong(),
        2,
    )

    private class HeldReturnDispatcher : CoroutineDispatcher() {
        private val pending = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.add(block)
        }
        fun hasReturn(): Boolean = pending.isNotEmpty()
        fun resumePending() {
            pending.poll()?.run()
        }
    }
}
