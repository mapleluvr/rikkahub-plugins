package dev.rikkahub.plugins.core

import dev.rikkahub.plugins.protocol.PluginReleaseIndex
import dev.rikkahub.plugins.protocol.PluginReleasePackage
import dev.rikkahub.plugins.protocol.PluginReleaseSignature
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object PluginReleaseContract {
    const val MAX_INDEX_BYTES = 64 * 1024

    fun index(bytes: ByteArray): PluginReleaseIndex {
        require(bytes.size in 1..MAX_INDEX_BYTES)
        return PluginContract.json.decodeFromString<PluginReleaseIndex>(bytes.toString(Charsets.UTF_8)).also { index ->
            require(index.schemaVersion == 1 && index.packages.size in 1..64)
            require(index.packages.map { it.id }.distinct().size == index.packages.size)
            require(index.packages.map { it.asset.lowercase() }.distinct().size == index.packages.size)
            index.packages.forEach { pkg ->
                require(pkg.id.length <= 120 && Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9_-]*)+").matches(pkg.id))
                PluginVersion.parse(pkg.version)
                PluginArchive.requireHash(pkg.sha256)
                PluginContract.path(pkg.asset)
                require('/' !in pkg.asset && pkg.asset.endsWith(".rikkaplug"))
                require(pkg.sizeBytes in 22..PluginArchive.MAX_ARCHIVE_BYTES)
                require(pkg.apiVersion.length in 1..16 && pkg.runtime.length in 1..80)
                require(pkg.requiredFeatures.size <= 64 && pkg.capabilities.size <= 64)
                require((pkg.requiredFeatures + pkg.capabilities).all { it.length in 1..120 })
            }
        }
    }

    fun compatible(pkg: PluginReleasePackage): Boolean = pkg.apiVersion == "1" && pkg.runtime == "quickjs-es2020" &&
        PluginContract.features.containsAll(pkg.requiredFeatures) &&
        PluginContract.capabilities.containsAll(pkg.capabilities)

    fun verifyPackage(entry: PluginReleasePackage, bytes: ByteArray): VerifiedPluginPackage {
        require(bytes.size == entry.sizeBytes) { "Release asset size changed" }
        return PluginArchive.decode(bytes, entry.sha256).also { pkg ->
            val manifest = pkg.manifest
            require(
                entry.id == manifest.id && entry.version == manifest.version && entry.apiVersion == manifest.apiVersion &&
                    entry.runtime == manifest.runtime && entry.requiredFeatures == manifest.requiredFeatures &&
                    entry.capabilities == manifest.capabilities
            ) { "Release index disagrees with package manifest" }
        }
    }

    /** Returns a fingerprint to present for first trust; verification alone does not establish publisher identity. */
    fun verifySignature(index: ByteArray, envelope: ByteArray): String {
        require(index.size in 1..MAX_INDEX_BYTES && envelope.size in 1..4096)
        val signed = PluginContract.json.decodeFromString<PluginReleaseSignature>(envelope.toString(Charsets.UTF_8))
        require(signed.algorithm == "ES256")
        val keyBytes = Base64.getDecoder().decode(signed.publicKey)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes)) as ECPublicKey
        val parameters = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        require(key.params.curve == parameters.curve && key.params.generator == parameters.generator &&
            key.params.order == parameters.order && key.params.cofactor == parameters.cofactor) {
            "Expected P-256 signing key"
        }
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(index)
            verify(Base64.getDecoder().decode(signed.signature))
        }) { "Invalid release signature" }
        return MessageDigest.getInstance("SHA-256").digest(keyBytes).joinToString("") { "%02x".format(it) }
    }
}
