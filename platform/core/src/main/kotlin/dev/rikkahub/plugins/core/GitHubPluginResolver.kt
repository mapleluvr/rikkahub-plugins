package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.PluginReleaseOrigin
import dev.rikkahub.plugins.protocol.PluginReleasePackage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Transport accepts API-relative paths only. Credentials and redirect policy belong to the host. */
fun interface GitHubPluginTransport {
    suspend fun get(path: String, binary: Boolean, limit: Int, etag: String?): GitHubPluginResponse
}

data class GitHubPluginResponse(val status: Int, val bytes: ByteArray, val etag: String? = null)
data class PluginReleaseCandidate(
    val entry: PluginReleasePackage,
    val origin: PluginReleaseOrigin,
    val notes: String,
    val indexHash: String,
)
data class PluginReleaseDiscovery(
    val repositoryId: Long,
    val repository: String,
    val candidates: List<PluginReleaseCandidate>,
    val releasesChecked: Int,
    val truncated: Boolean,
    val rejected: List<String>,
)

/** Discovery is bounded and does not change installed state. A download revalidates the exact release. */
class GitHubPluginResolver(private val transport: GitHubPluginTransport, private val maxPages: Int = 5) {
    private val requests = Mutex()
    private val cache = linkedMapOf<String, GitHubPluginResponse>()

    init {
        require(maxPages in 1..10)
    }

    suspend fun discover(source: GitHubPluginSource, includePrerelease: Boolean = false): PluginReleaseDiscovery {
        val repo = repository(source.repository)
        val name = repo.text("full_name")
        val repoId = repo.number("id")
        GitHubPluginSource.parse(name)
        val candidates = mutableListOf<PluginReleaseCandidate>()
        val rejected = mutableListOf<String>()
        var checked = 0
        var truncated = false
        val pages = if (source.tag == null) maxPages else 1
        for (page in 1..pages) {
            val releases = if (source.tag == null) {
                json("repos/$name/releases?per_page=30&page=$page") as? JsonArray
                    ?: error("Invalid GitHub release list")
            } else {
                JsonArray(listOf(json("repos/$name/releases/tags/${GitHubPluginSource.encodeSegment(source.tag)}")))
            }
            require(releases.size <= 30) { "GitHub page exceeds limit" }
            for (element in releases) {
                val release = element.jsonObject
                checked++
                if (release.flag("draft") || release.flag("prerelease") && !includePrerelease) continue
                try {
                    val assets = assets(release)
                    val indexAsset = assets.singleOrNull { it.text("name") == "release.json" } ?: continue
                    // Transport failures still propagate. Malformed assets invalidate only their release.
                    val raw = asset(name, indexAsset, PluginReleaseContract.MAX_INDEX_BYTES)
                    val signatureAsset = assets.singleOrNull { it.text("name") == "release.json.sig" }
                    val signature = signatureAsset?.let { asset(name, it, 4096) }
                    val fromRelease = mutableListOf<PluginReleaseCandidate>()
                    val index = PluginReleaseContract.index(raw)
                    val key = signature?.let { PluginReleaseContract.verifySignature(raw, it) }
                    for (entry in index.packages) {
                        require(PluginVersion.parse(entry.version).prerelease == release.flag("prerelease")) {
                            "Release channel disagrees with package version"
                        }
                        if (!PluginReleaseContract.compatible(entry)) continue
                        val binary = requireNotNull(assets.singleOrNull { it.text("name") == entry.asset }) {
                            "Missing release asset"
                        }
                        require(binary.number("size") == entry.sizeBytes.toLong()) {
                            "Release asset size disagrees with index"
                        }
                        fromRelease += PluginReleaseCandidate(
                            entry,
                            PluginReleaseOrigin(
                                repoId,
                                name,
                                release.number("id"),
                                release.text("tag_name"),
                                binary.number("id"),
                                key
                            ),
                            (release["body"]?.jsonPrimitive?.content ?: "").take(16000),
                            PluginArchive.hash(raw)
                        )
                    }
                    candidates += fromRelease
                } catch (error: IllegalArgumentException) {
                    rejected += "${release.text("tag_name")}: ${error.message}"
                } catch (_: java.security.GeneralSecurityException) {
                    rejected += "${release.text("tag_name")}: Invalid release signing key or signature"
                }
            }
            if (source.tag != null || releases.size < 30) break
            truncated = page == pages
        }
        // A mutable tag must not offer two different packages under one semantic version identity.
        candidates.groupBy { it.entry.id to it.entry.version }.values.forEach { group ->
            require(group.map {
                it.entry.sha256
            }.distinct().size == 1) { "Same plugin version has different release bytes" }
        }
        return PluginReleaseDiscovery(
            repoId,
            name,
            candidates.distinctBy { it.entry.id to it.entry.sha256 }
                .sortedWith(compareBy<PluginReleaseCandidate> {
                    it.entry.id
                }.thenByDescending { PluginVersion.parse(it.entry.version) }),
            checked,
            truncated,
            rejected
        )
    }

    suspend fun download(candidate: PluginReleaseCandidate): ByteArray {
        val origin = candidate.origin
        val repo = repository(origin.repository)
        require(repo.number("id") == origin.repositoryId) { "GitHub repository identity changed" }
        val name = repo.text("full_name")
        GitHubPluginSource.parse(name)
        val release = json("repos/$name/releases/${origin.releaseId}").jsonObject
        require(
            !release.flag("draft") && release.number("id") == origin.releaseId && release.text("tag_name") == origin.tag
        )
        require(release.flag("prerelease") == PluginVersion.parse(candidate.entry.version).prerelease)
        val assets = assets(release)
        val raw = asset(
            name,
            requireNotNull(assets.singleOrNull { it.text("name") == "release.json" }),
            PluginReleaseContract.MAX_INDEX_BYTES
        )
        require(PluginArchive.hash(raw) == candidate.indexHash) { "Release index changed after selection" }
        require(candidate.entry in PluginReleaseContract.index(raw).packages)
        val signature = assets.singleOrNull { it.text("name") == "release.json.sig" }?.let { asset(name, it, 4096) }
        require(signature?.let { PluginReleaseContract.verifySignature(raw, it) } == origin.publisherKey) {
            "Publisher identity changed after selection"
        }
        val binary = requireNotNull(assets.singleOrNull {
            it.number("id") == origin.assetId &&
                it.text("name") == candidate.entry.asset
        }) {
            "Selected release asset was replaced"
        }
        return asset(name, binary, PluginArchive.MAX_ARCHIVE_BYTES).also {
            PluginReleaseContract.verifyPackage(candidate.entry, it)
        }
    }

    private suspend fun repository(name: String): JsonObject {
        require(GitHubPluginSource.parse(name).tag == null)
        return json("repos/$name").jsonObject.also { require(it.number("id") > 0) }
    }

    private fun assets(release: JsonObject): List<JsonObject> {
        val assets = (release["assets"] as? JsonArray ?: error("Invalid release assets")).map { it.jsonObject }
        require(assets.size <= 128 && assets.map { it.text("name") }.distinct().size == assets.size)
        require(assets.all { it.number("id") > 0 })
        return assets
    }

    private suspend fun json(path: String) = Json.parseToJsonElement(
        request(path, false, 2 * 1024 * 1024).toString(Charsets.UTF_8)
    )
    private suspend fun asset(repo: String, asset: JsonObject, limit: Int): ByteArray {
        require(asset.number("size") in 1..limit.toLong()) { "Release asset exceeds limit" }
        return request("repos/$repo/releases/assets/${asset.number("id")}", true, limit).also {
            require(it.size.toLong() == asset.number("size")) { "Release asset is incomplete" }
        }
    }

    private suspend fun request(path: String, binary: Boolean, limit: Int): ByteArray = requests.withLock {
        val key = "$binary:$path"
        val cached = cache[key]
        val response = transport.get(path, binary, limit, cached?.etag)
        val bytes = if (response.status ==
            304) {
            requireNotNull(cached) { "Unexpected GitHub cache response" }.bytes
        } else {
            check(response.status == 200) {
                "GitHub request failed (${response.status}); installed plugins remain available"
            }
            require(response.bytes.size <= limit) { "GitHub response exceeds limit" }
            if (response.etag != null && response.bytes.size <= 256 * 1024) {
                cache[key] = response.copy(bytes = response.bytes.copyOf())
                while (cache.size > 64) cache.remove(cache.keys.first())
            }
            response.bytes
        }
        require(bytes.size <= limit)
        bytes.copyOf()
    }

    private fun JsonObject.text(key: String) = requireNotNull(this[key]).jsonPrimitive.content
    private fun JsonObject.number(key: String) = requireNotNull(this[key]).jsonPrimitive.long
    private fun JsonObject.flag(key: String) = requireNotNull(this[key]).jsonPrimitive.boolean
}
