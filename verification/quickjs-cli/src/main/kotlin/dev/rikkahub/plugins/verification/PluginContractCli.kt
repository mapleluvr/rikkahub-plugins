package dev.rikkahub.plugins.verification

import dev.rikkahub.plugins.core.PluginArchive
import dev.rikkahub.plugins.core.PluginContract
import dev.rikkahub.plugins.core.PluginReleaseContract
import dev.rikkahub.plugins.protocol.PackageLease
import dev.rikkahub.plugins.runtime.PluginInstance
import kotlinx.coroutines.runBlocking
import java.io.File

/** Same host archive validation and QuickJS preflight; no Android SDK, app initialization or network permissions. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 1) { "Usage: rikka-plugin-contract <release-directory>" }
    val directory = File(args.single()).canonicalFile
    val indexBytes = File(directory, "release.json").inputStream().use {
        PluginArchive.readBounded(it, PluginReleaseContract.MAX_INDEX_BYTES)
    }
    val index = PluginReleaseContract.index(indexBytes)
    val signature = File(directory, "release.json.sig")
    if (signature.exists()) {
        PluginReleaseContract.verifySignature(
            indexBytes,
            signature.inputStream().use { PluginArchive.readBounded(it, 4096) }
        )
    }
    for (entry in index.packages) {
        val file = File(directory, entry.asset).canonicalFile
        require(file.parentFile == directory) { "Package escapes release directory" }
        val pkg = PluginReleaseContract.verifyPackage(entry, file.inputStream().use {
            PluginArchive.readBounded(it, PluginArchive.MAX_ARCHIVE_BYTES)
        })
        val instance = PluginInstance.prepare(
            pkg,
            PackageLease(pkg.manifest.id, pkg.hash, 1),
            authorized = { _, _ -> true },
            readContext = { error("Preflight must not read host context") }
        )
        try {
            for (panel in pkg.manifest.panels) {
                // Rendering must be bounded and side-effect free, with actions confined to declared operations.
                PluginContract.ui(pkg.manifest, PluginContract.json.encodeToString(instance.panel(panel.id)))
            }
            println("Verified ${pkg.manifest.id}@${pkg.manifest.version} ${pkg.hash}")
        } finally {
            instance.close()
        }
    }
}
