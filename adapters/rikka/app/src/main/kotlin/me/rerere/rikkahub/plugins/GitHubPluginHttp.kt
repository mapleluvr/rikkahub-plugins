package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.GitHubPluginResponse
import dev.rikkahub.plugins.core.GitHubPluginTransport
import dev.rikkahub.plugins.core.PluginArchive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Host-only GitHub access. Redirects are explicit so credentials never accompany a CDN request. */
class GitHubPluginHttp(
    private val token: suspend () -> String?,
    client: OkHttpClient = OkHttpClient(),
    private val api: HttpUrl = "https://api.github.com/".toHttpUrl(),
) : GitHubPluginTransport {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    @Volatile private var blockedUntil = 0L

    init {
        require(api.username.isEmpty() && api.password.isEmpty() && api.query == null && api.encodedPath == "/")
        require(api.toString() == "https://api.github.com/" || api.host in setOf("127.0.0.1", "localhost"))
    }

    override suspend fun get(path: String, binary: Boolean, limit: Int, etag: String?): GitHubPluginResponse {
        val reply = fetch(path, binary, etag) { response ->
            require(response.body.contentLength() <= limit) { "GitHub response exceeds byte limit" }
            response.body.byteStream().use { PluginArchive.readBounded(it, limit) }
        }
        return GitHubPluginResponse(reply.status, reply.value ?: byteArrayOf(), reply.etag)
    }

    /** The caller owns the staging file. The writer has closed it before this call returns or cancels. */
    internal suspend fun downloadAsset(path: String, destination: File, expectedBytes: Long) {
        require(expectedBytes > 0)
        val reply = fetch(path, binary = true, etag = null, timeoutSeconds = 300) { response ->
            val length = response.body.contentLength()
            require(length == -1L || length == expectedBytes) { "GitHub asset length differs from the index" }
            val caller = currentCoroutineContext()
            response.body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        caller.ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        copied += count
                        require(copied <= expectedBytes) { "GitHub asset exceeds the indexed size" }
                        output.write(buffer, 0, count)
                    }
                    require(copied == expectedBytes) { "GitHub asset is incomplete" }
                }
            }
        }
        check(reply.status == 200) { "GitHub download failed (${reply.status})" }
    }

    private suspend fun <T> fetch(
        path: String,
        binary: Boolean,
        etag: String?,
        timeoutSeconds: Long = 30,
        consume: suspend (Response) -> T,
    ): Reply<T> {
        require(path.startsWith("repos/") && !path.contains('\\') && !path.contains('#'))
        check(System.currentTimeMillis() >= blockedUntil) { "GitHub rate limit is active; retry later" }
        val credential = token()?.takeIf { it.isNotBlank() }
        var target = requireNotNull(api.resolve(path))
        require(sameOrigin(target, api))
        repeat(6) { attempt ->
            val request = Request.Builder().url(target)
                .header("Accept", if (binary) "application/octet-stream" else "application/vnd.github+json")
                .header("User-Agent", "RikkaHub-PluginHost")
                .apply {
                    if (sameOrigin(target, api)) {
                        header("X-GitHub-Api-Version", "2022-11-28")
                        if (credential != null) header("Authorization", "Bearer $credential")
                        if (etag != null) header("If-None-Match", etag)
                    }
                }.build()
            val result = exchange(request, timeoutSeconds, consume)
            if (result.status in setOf(301, 302, 303, 307, 308)) {
                check(attempt < 5) { "Too many GitHub redirects" }
                val next = requireNotNull(result.location?.let { target.resolve(it) }) { "Invalid GitHub redirect" }
                require(next.username.isEmpty() && next.password.isEmpty() && next.fragment == null)
                require(sameOrigin(next, api) || binary && next.scheme == "https" && next.port == 443 &&
                    next.host in
                    setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github.com")) {
                    "Untrusted GitHub download redirect"
                }
                target = next
            } else {
                if (result.status in setOf(403, 429)) {
                    val seconds = result.retryAfter?.toLongOrNull()?.coerceIn(1, 3600) ?: 60L
                    blockedUntil = System.currentTimeMillis() + seconds * 1000
                }
                return result
            }
        }
        error("Too many GitHub redirects")
    }

    private suspend fun <T> exchange(
        request: Request,
        timeoutSeconds: Long,
        consume: suspend (Response) -> T,
    ): Reply<T> = withContext(Dispatchers.IO) {
            coroutineScope {
                val call = http.newCall(request)
                call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
                // Cancel the blocking socket even when every IO dispatcher thread is busy.
                val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { response ->
                        val value = if (response.code == 200) consume(response) else null
                        Reply(
                            response.code,
                            value,
                            response.header("ETag"),
                            response.header("Location"),
                            response.header("Retry-After"),
                        )
                    }
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw IOException("GitHub connection failed", error)
                } finally {
                    cancellation.cancel()
                }
            }
        }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    private data class Reply<T>(
        val status: Int,
        val value: T?,
        val etag: String?,
        val location: String?,
        val retryAfter: String?
    )
}
