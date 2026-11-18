package me.rerere.rikkahub.update

import dev.rikkahub.plugins.core.GitHubPluginTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest
import java.time.Instant

private const val MAX_INDEX_BYTES = 64 * 1024
private const val MAX_METADATA_BYTES = 1024 * 1024
private const val RELEASE_PAGE_SIZE = 30
private const val MAX_RELEASE_PAGES = 8
private const val MAX_VERSION_CODE = 2_100_000_000
private val releaseTag = Regex("host-v([1-9][0-9]{0,9})")
internal const val MAX_HOST_APK_BYTES = 256 * 1024 * 1024L
private val digestPattern = Regex("[a-f0-9]{64}")
private val assetNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}\\.apk")
private val versionNamePattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)*")
private val supportedReleaseAbis = setOf("arm64-v8a", "x86_64")

@Serializable
internal data class HostApkArtifact(val name: String, val abi: String, val sizeBytes: Long, val sha256: String)

@Serializable
private data class HostReleaseIndex(
    val schemaVersion: Int,
    val applicationId: String,
    val certificateSha256: String,
    val versionCode: Int,
    val versionName: String,
    val sourceProvenanceSha256: String,
    val notes: String,
    val apks: List<HostApkArtifact>,
)

internal data class HostUpdateCandidate(
    val versionCode: Int,
    val versionName: String,
    val artifact: HostApkArtifact,
    val notes: String,
    val publishedAt: String,
    val repositoryId: Long,
    val releaseId: Long,
    val indexAssetId: Long,
    val indexSha256: String,
    val apkAssetId: Long,
)

/** Resolves one published host release and revalidates the selected index and APK identity before download. */
internal class HostReleaseResolver(
    private val transport: GitHubPluginTransport,
    repository: String,
    private val indexName: String,
    private val applicationId: String,
    private val certificateSha256: String,
    private val installedVersionCode: Int,
    private val supportedAbis: List<String>,
) {
    private val repositoryName = repository.also {
        require(Regex("[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9][A-Za-z0-9._-]*").matches(it))
    }
    private val repoPath = "repos/$repositoryName"

    suspend fun discover(): HostUpdateCandidate? {
        val repoId = repositoryId()
        val releases = linkedMapOf<Int, JsonObject>()
        for (page in 1..MAX_RELEASE_PAGES) {
            val batch = metadata("$repoPath/releases?per_page=$RELEASE_PAGE_SIZE&page=$page") as? JsonArray
                ?: error("Invalid GitHub release list")
            require(batch.size <= RELEASE_PAGE_SIZE) { "GitHub release page exceeds its limit" }
            for (entry in batch) {
                val release = entry.jsonObject
                val version = versionFromTag(release.text("tag_name")) ?: continue
                if (version <= installedVersionCode || release.flag("draft") || release.flag("prerelease")) continue
                require(releases.putIfAbsent(version, release) == null) { "Duplicate or unstable host releases" }
            }
            if (batch.size < RELEASE_PAGE_SIZE) {
                return releases.maxByOrNull { it.key }?.value?.let { candidate(repoId, it) }
            }
        }
        error("Host release scan limit reached")
    }

    suspend fun confirmDownload(selected: HostUpdateCandidate): String {
        val repoId = repositoryId()
        require(repoId == selected.repositoryId) { "Host update repository changed" }
        val release = metadata("$repoPath/releases/${selected.releaseId}").jsonObject
        require(candidate(repoId, release) == selected) { "Host release or selected asset changed" }
        return assetPath(selected.apkAssetId)
    }

    private suspend fun repositoryId(): Long {
        val repo = metadata(repoPath).jsonObject
        require(repo.text("full_name").equals(repositoryName, ignoreCase = true)) { "Host update repository changed" }
        return repo.positive("id")
    }

    private suspend fun candidate(repoId: Long, release: JsonObject): HostUpdateCandidate? {
        require(!release.flag("draft") && !release.flag("prerelease")) { "Host release must be published and stable" }
        val tag = release.text("tag_name")
        val version = versionFromTag(tag) ?: return null
        if (version <= installedVersionCode) return null
        val publishedAt = release.text("published_at").also(Instant::parse)
        val assets = releaseAssets(release)
        val indexAsset = requireNotNull(assets[indexName]) { "Host release index is missing" }
        require(indexAsset.size in 1..MAX_INDEX_BYTES.toLong()) { "Host release index exceeds its size limit" }
        val response = transport.get(assetPath(indexAsset.id), binary = true, limit = MAX_INDEX_BYTES, etag = null)
        check(response.status == 200) { "Host release index request failed (${response.status})" }
        require(response.bytes.size.toLong() == indexAsset.size) { "Host release index is incomplete" }
        val indexSha = MessageDigest.getInstance("SHA-256").digest(response.bytes).toHexString()
        require(indexSha == indexAsset.sha256) { "Host release index digest mismatch" }
        val index = Json.decodeFromString<HostReleaseIndex>(
            response.bytes.decodeToString(throwOnInvalidSequence = true)
        )
        validateIndex(index, tag)
        val artifact = supportedAbis.firstNotNullOfOrNull { abi -> index.apks.find { it.abi == abi } }
            ?: index.apks.find { it.abi == "universal" && supportedAbis.any(supportedReleaseAbis::contains) }
            ?: return null
        val apk = requireNotNull(assets[artifact.name]) { "Host APK asset is missing" }
        require(apk.size == artifact.sizeBytes && apk.sha256 == artifact.sha256) { "Host APK size or digest mismatch" }
        return HostUpdateCandidate(
            index.versionCode, index.versionName, artifact, index.notes, publishedAt, repoId,
            release.positive("id"), indexAsset.id, indexSha, apk.id,
        )
    }

    private fun validateIndex(index: HostReleaseIndex, tag: String) {
        require(index.schemaVersion == 1) { "Unsupported host release index format" }
        require(index.applicationId == applicationId) { "Host APK application ID mismatch" }
        require(index.certificateSha256 == certificateSha256) { "Host APK certificate mismatch" }
        require(versionFromTag(tag) == index.versionCode) {
            "Host release tag and version code disagree"
        }
        require(index.versionName.length <= 96 && versionNamePattern.matches(index.versionName)) {
            "Invalid host version name"
        }
        require(digestPattern.matches(index.sourceProvenanceSha256) && index.notes.length <= 16_000) {
            "Invalid host release metadata"
        }
        require(
            index.apks.size in 1..3 && index.apks.distinctBy { it.name }.size == index.apks.size &&
                index.apks.distinctBy { it.abi }.size == index.apks.size
        ) { "Ambiguous host APK manifest" }
        require(index.apks.all { artifact ->
            (artifact.abi in supportedReleaseAbis || artifact.abi == "universal") &&
                assetNamePattern.matches(artifact.name) && digestPattern.matches(artifact.sha256) &&
                artifact.sizeBytes in 1..MAX_HOST_APK_BYTES
        }) { "Invalid host APK information" }
    }

    private fun releaseAssets(release: JsonObject): Map<String, ReleaseAsset> {
        val list = release.getValue("assets") as? JsonArray ?: error("Invalid GitHub assets")
        require(list.size <= 128) { "Too many host release assets" }
        val assets = list.map { element ->
            val asset = element.jsonObject
            val digest = asset["digest"]?.jsonPrimitive?.content?.takeIf { value -> value.startsWith("sha256:") }
                ?.removePrefix("sha256:")?.takeIf(digestPattern::matches)
            ReleaseAsset(asset.positive("id"), asset.text("name"), asset.positive("size"), digest)
        }
        require(assets.distinctBy { it.id }.size == assets.size && assets.distinctBy { it.name }.size == assets.size) {
            "Ambiguous GitHub assets"
        }
        return assets.associateBy(ReleaseAsset::name)
    }

    private suspend fun metadata(path: String): kotlinx.serialization.json.JsonElement {
        val response = transport.get(path, binary = false, limit = MAX_METADATA_BYTES, etag = null)
        check(response.status == 200) { "Host update request failed (${response.status})" }
        require(response.bytes.size <= MAX_METADATA_BYTES) { "Host release metadata exceeds its byte limit" }
        return Json.parseToJsonElement(response.bytes.decodeToString(throwOnInvalidSequence = true))
    }

    private fun versionFromTag(tag: String): Int? = releaseTag.matchEntire(tag)?.groupValues?.get(1)
        ?.toIntOrNull()?.takeIf { it <= MAX_VERSION_CODE }

    private fun assetPath(id: Long) = "$repoPath/releases/assets/$id"
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.positive(key: String) = getValue(key).jsonPrimitive.long.also { require(it > 0) }

    private data class ReleaseAsset(val id: Long, val name: String, val size: Long, val sha256: String?)
}
