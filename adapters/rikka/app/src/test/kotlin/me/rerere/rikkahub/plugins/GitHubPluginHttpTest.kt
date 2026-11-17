package me.rerere.rikkahub.plugins

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class GitHubPluginHttpTest {
    @Test fun localHttpChecksSizeEtagRateLimitAndCancelsAnOpenDownload() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val headers = CopyOnWriteArrayList<String>()
        val requests = AtomicInteger()
        val streaming = CompletableDeferred<Unit>()
        val finishStream = java.util.concurrent.CountDownLatch(1)
        server.createContext("/repos/test/repo") { exchange ->
            requests.incrementAndGet()
            headers += exchange.requestHeaders.getFirst("Authorization") ?: ""
            try {
                when (exchange.requestURI.query) {
                    "large" -> {
                        exchange.sendResponseHeaders(200, 128)
                        exchange.responseBody.write(ByteArray(128))
                    }

                    "limit" -> {
                        exchange.responseHeaders.add("Retry-After", "60")
                        exchange.sendResponseHeaders(429, -1)
                    }

                    "stream" -> {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.write(byteArrayOf(1))
                        exchange.responseBody.flush()
                        streaming.complete(Unit)
                        // The test's cancellation closes the socket; this latch is released in finally.
                        finishStream.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }

                    else -> {
                        if (exchange.requestHeaders.getFirst("If-None-Match") ==
                            "same") {
                            exchange.sendResponseHeaders(304, -1)
                        } else {
                            exchange.responseHeaders.add("ETag", "same")
                            val body = "actual HTTP".toByteArray()
                            exchange.sendResponseHeaders(200, body.size.toLong())
                            exchange.responseBody.write(body)
                        }
                    }
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        val client = OkHttpClient()
        try {
            val transport = GitHubPluginHttp(
                { "fixture-token" },
                client,
                "http://127.0.0.1:${server.address.port}/".toHttpUrl()
            )
            assertEquals(
                "actual HTTP",
                transport.get("repos/test/repo", false, 64, null).bytes.toString(Charsets.UTF_8)
            )
            assertEquals(304, transport.get("repos/test/repo", false, 64, "same").status)
            assertTrue(runCatching { transport.get("repos/test/repo?large", true, 64, null) }.isFailure)
            val call = async { transport.get("repos/test/repo?stream", true, 256, null) }
            withTimeout(3000) { streaming.await() }
            withTimeout(3000) { call.cancelAndJoin() }
            assertEquals(429, transport.get("repos/test/repo?limit", false, 64, null).status)
            val count = requests.get()
            assertTrue(runCatching { transport.get("repos/test/repo", false, 64, null) }.isFailure)
            assertEquals(count, requests.get())
            assertTrue(headers.all { it == "Bearer fixture-token" })
        } finally {
            finishStream.countDown()
            server.stop(0)
            executor.shutdownNow()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun approvedCdnRedirectNeverCarriesCredentialAndUnknownHostsAreRejected() = runBlocking {
        val seen = CopyOnWriteArrayList<okhttp3.Request>()
        var redirect = "https://release-assets.githubusercontent.com/release?temporary=fixture"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.url.host == "api.github.com") 302 else 200).message("fixture")
                .apply { if (request.url.host == "api.github.com") header("Location", redirect) }
                .body("asset".toResponseBody()).build()
        }.build()
        try {
            val transport = GitHubPluginHttp({ "fixture-secret" }, client)
            assertEquals(
                "asset",
                transport.get("repos/owner/repo/releases/assets/1", true, 32, "etag").bytes.toString(Charsets.UTF_8)
            )
            assertEquals("Bearer fixture-secret", seen[0].header("Authorization"))
            assertEquals(null, seen[1].header("Authorization"))
            assertEquals(null, seen[1].header("If-None-Match"))
            redirect = "https://unexpected.invalid/asset"
            assertTrue(runCatching { transport.get("repos/owner/repo/releases/assets/1", true, 32, null) }.isFailure)
            assertFalse(seen.any { it.url.host == "unexpected.invalid" })
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
