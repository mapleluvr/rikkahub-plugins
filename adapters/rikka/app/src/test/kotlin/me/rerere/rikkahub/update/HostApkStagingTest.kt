package me.rerere.rikkahub.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.plugins.GitHubPluginHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HostApkStagingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun stagesOnlyACompleteResponseAndDeletesRejectedFiles() = runBlocking {
        withServer { http ->
            val storage = temp.newFolder()
            val staging = HostApkStaging(storage)
            val bytes = byteArrayOf(1, 2, 3, 4)
            val good = staging.prepare(
                download = { http.downloadAsset("repos/owner/repo/complete", it, bytes.size.toLong()) },
                verify = { assertArrayEquals(bytes, it.readBytes()) },
            )
            assertArrayEquals(bytes, good.readBytes())
            assertTrue(good.delete())

            for (path in listOf("short", "large", "wrong-length")) {
                var verified = false
                val result = runCatching {
                    staging.prepare(
                        download = { http.downloadAsset("repos/owner/repo/$path", it, 4) },
                        verify = { verified = true },
                    )
                }
                assertTrue("$path should fail", result.isFailure)
                assertFalse("Incomplete response reached APK verification", verified)
                assertTrue(storage.listFiles()!!.isEmpty())
            }
            val rejected = runCatching {
                staging.prepare(
                    download = { http.downloadAsset("repos/owner/repo/complete", it, 4) },
                    verify = { error("APK signature check failed") },
                )
            }
            assertTrue(rejected.isFailure)
            assertTrue(storage.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun cancellationClosesTheWriterAndRemovesPartialFileBeforeTheServerCloses() = runBlocking {
        val release = CountDownLatch(1)
        val prefixSent = CompletableDeferred<Unit>()
        withServer(release, prefixSent) { http ->
            val storage = temp.newFolder()
            val staging = HostApkStaging(storage)
            val call = async {
                staging.prepare(
                    download = { http.downloadAsset("repos/owner/repo/blocked", it, 1024) },
                    verify = { error("Cancelled download must not be verified") },
                )
            }
            try {
                withTimeout(5000) {
                    prefixSent.await()
                    while (storage.listFiles()?.singleOrNull()?.length() != 2L) delay(10)
                    call.cancelAndJoin()
                }
                assertTrue(storage.listFiles()!!.isEmpty())
                assertTrue(release.count == 1L)
            } finally {
                release.countDown()
            }
        }
    }

    private suspend fun withServer(
        release: CountDownLatch = CountDownLatch(0),
        prefixSent: CompletableDeferred<Unit> = CompletableDeferred(),
        action: suspend (GitHubPluginHttp) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val worker = Executors.newCachedThreadPool()
        server.executor = worker
        server.createContext("/repos/owner/repo/") { exchange ->
            try {
                val path = exchange.requestURI.path.substringAfterLast('/')
                val body = when (path) {
                    "short", "blocked" -> byteArrayOf(1, 2)
                    "large", "wrong-length" -> byteArrayOf(1, 2, 3, 4, 5)
                    else -> byteArrayOf(1, 2, 3, 4)
                }
                val lengthsAreChunked = path in setOf("short", "large", "blocked")
                exchange.sendResponseHeaders(200, if (lengthsAreChunked) 0 else body.size.toLong())
                exchange.responseBody.write(body)
                exchange.responseBody.flush()
                if (path == "blocked") {
                    prefixSent.complete(Unit)
                    release.await(15, TimeUnit.SECONDS)
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            action(GitHubPluginHttp({ null }, api = "http://127.0.0.1:${server.address.port}/".toHttpUrl()))
        } finally {
            release.countDown()
            server.stop(0)
            worker.shutdownNow()
        }
    }
}
