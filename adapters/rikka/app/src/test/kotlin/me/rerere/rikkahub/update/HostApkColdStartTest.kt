package me.rerere.rikkahub.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Forks real JVM processes so old in-memory owners cannot help the next download. */
class HostApkColdStartTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun repeatedColdStartsReclaimVerifiedFilesBeforeTheyExhaustTheDownloadBudget() {
        val storage = temp.newFolder()
        repeat(5) { restart ->
            runReadyProcess(storage)
            assertEquals(
                "Cold start $restart retained an earlier unhanded APK",
                1,
                apkFiles(storage).size,
            )
        }
    }

    @Test
    fun aKilledDownloadDoesNotConsumeTheNextProcessesStorageBudget() {
        val storage = temp.newFolder()
        val output = temp.newFile()
        val process = startProcess(storage, "partial", output)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!output.readText(Charsets.UTF_8).contains("PREFIX_WRITTEN")) {
                assertTrue("Download fixture exited before writing a prefix: ${output.readText()}", process.isAlive)
                assertTrue("Download fixture did not reach its owned-file boundary", System.nanoTime() < deadline)
                Thread.sleep(10)
            }
            assertTrue("No partial file was actually created", apkFiles(storage).isNotEmpty())
        } finally {
            process.destroyForcibly()
            assertTrue("Owned fixture process did not exit", process.waitFor(20, TimeUnit.SECONDS))
        }
        runReadyProcess(storage)
        assertEquals("The killed downloader still owns a storage slot", 1, apkFiles(storage).size)
    }

    @Test
    fun grantedMaterialSurvivesARealColdStartAndIsReusedBeforeChargingCapacity() = runBlocking {
        val storage = temp.newFolder()
        val original = runReadyProcess(storage, "granted")
        val originalFile = apkFiles(storage).single()
        assertTrue(original.contains("DOWNLOADED"))
        repeat(4) {
            val reopened = runReadyProcess(storage, "ready-target")
            assertFalse("Cold start downloaded another copy", reopened.contains("DOWNLOADED"))
            assertEquals(originalFile, apkFiles(storage).single())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), originalFile.readBytes())
        }
        HostApkStaging(storage).finishInstall(installationTicket(original))
        assertTrue(storage.listFiles()!!.isEmpty())
    }

    @Test
    fun aNewProcessRefusesAnOldBridgeRequestBeforeReclaimingItsMaterial() = runBlocking {
        val storage = temp.newFolder()
        val original = runReadyProcess(storage, "pending")
        val ticket = installationTicket(original)
        val staging = HostApkStaging(storage)
        assertTrue(runCatching { staging.authorizeInstaller(ticket) }.isFailure)
        assertTrue(apkFiles(storage).single().isFile)
        val current = staging.prepare(download = { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }, verify = {})
        assertFalse(staging.hasInstall(ticket))
        assertEquals(1, apkFiles(storage).size)
        staging.discard(current)
        assertTrue(storage.listFiles()!!.isEmpty())
    }

    private fun runReadyProcess(storage: File, mode: String = "ready"): String {
        val output = temp.newFile()
        val process = startProcess(storage, mode, output)
        try {
            assertTrue("Owned fixture process timed out", process.waitFor(20, TimeUnit.SECONDS))
            assertEquals(output.readText(Charsets.UTF_8), 0, process.exitValue())
            assertTrue(output.readText(Charsets.UTF_8).contains("READY"))
            return output.readText(Charsets.UTF_8)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(20, TimeUnit.SECONDS))
            }
        }
    }

    private fun installationTicket(output: String): HostInstallTicket {
        val serialized = output.lineSequence().single { it.startsWith("TICKET:") }.removePrefix("TICKET:")
        return Json.decodeFromString(serialized)
    }

    private fun startProcess(storage: File, mode: String, output: File): Process {
        val dependencies = listOf(
            HostApkStagingProcessFixture::class.java,
            HostApkStaging::class.java,
            CoroutineScope::class.java,
            Unit::class.java,
            Json::class.java,
            KSerializer::class.java,
        )
        val classpath = dependencies.map {
            File(requireNotNull(it.protectionDomain?.codeSource).location.toURI()).path
        }.distinct().joinToString(File.pathSeparator)
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$executable")
        return ProcessBuilder(
            java.path,
            "-cp",
            classpath,
            HostApkStagingProcessFixture::class.java.name,
            storage.absolutePath,
            mode,
        ).redirectErrorStream(true).redirectOutput(output).start()
    }

    private fun apkFiles(storage: File): List<File> = storage.listFiles().orEmpty().filter { it.extension == "apk" }
}

object HostApkStagingProcessFixture {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val staging = HostApkStaging(File(args[0]))
        val bytes = byteArrayOf(1, 2, 3, 4)
        val identity = if (args[1] in setOf("granted", "pending", "ready-target")) {
            HostApkIdentity(MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(), bytes.size.toLong(), 2)
        } else {
            null
        }
        val file = staging.prepare(
            download = {
                println("DOWNLOADED")
                it.writeBytes(bytes)
                if (args[1] == "partial") {
                    println("PREFIX_WRITTEN")
                    System.out.flush()
                    CountDownLatch(1).await()
                }
            },
            verify = { check(it.readBytes().contentEquals(bytes)) },
            identity = identity,
        )
        check(file.file.isFile)
        if (args[1] in setOf("granted", "pending")) {
            val ticket = staging.reserveInstall(file)
            if (args[1] == "granted") staging.authorizeInstaller(ticket)
            println("TICKET:" + Json.encodeToString(ticket))
        }
        if (args[1] in setOf("granted", "pending", "ready-target")) staging.discard(file)
        println("READY")
    }
}
