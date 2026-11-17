package me.rerere.rikkahub.plugins

import dev.rikkahub.plugins.core.GitHubPluginResponse
import dev.rikkahub.plugins.core.GitHubPluginTransport
import dev.rikkahub.plugins.core.PluginArchive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Host-only GitHub access. Redirects are explicit so credentials never accompany a CDN request. */
class GitHubPluginHttp(
    private val token: suspend () -> String?,
    client: OkHttpClient = OkHttpClient(),
    private val api: HttpUrl = "https://api.github.com/".toHttpUrl(),
) : GitHubPluginTransport {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()

    @Volatile private var blockedUntil = 0L

    init {
        require(api.username.isEmpty() && api.password.isEmpty() && api.query == null && api.encodedPath == "/")
        require(api.toString() == "https://api.github.com/" || api.host in setOf("127.0.0.1", "localhost"))
    }

    override suspend fun get(path: String, binary: Boolean, limit: Int, etag: String?): GitHubPluginResponse {
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
            val result = exchange(request, limit)
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
                return GitHubPluginResponse(result.status, result.bytes, result.etag)
            }
        }
        error("Too many GitHub redirects")
    }

    private suspend fun exchange(request: Request, limit: Int): Reply = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("GitHub connection failed", e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        val body = if (it.code == 200) {
                            require(it.body.contentLength() <= limit) { "GitHub response exceeds byte limit" }
                            it.body.byteStream().use { stream -> PluginArchive.readBounded(stream, limit) }
                        } else {
                            byteArrayOf()
                        }
                        Reply(it.code, body, it.header("ETag"), it.header("Location"), it.header("Retry-After"))
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    private data class Reply(
        val status: Int,
        val bytes: ByteArray,
        val etag: String?,
        val location: String?,
        val retryAfter: String?
    )
}
