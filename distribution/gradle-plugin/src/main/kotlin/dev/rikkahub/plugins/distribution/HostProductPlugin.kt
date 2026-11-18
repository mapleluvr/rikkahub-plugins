package dev.rikkahub.plugins.distribution

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/** Compiled by the host's existing build-logic and its pinned Android Gradle API. */
class HostProductPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") {
            // hostctl validates the closed schema and pins this exact public blob.
            val product = JsonSlurper().parseText(
                project.providers.fileContents(project.rootProject.layout.projectDirectory.file(
                    "vendor/rikka-plugin-host/distribution/product.json"
                )).asText.get()
            ) as? Map<*, *> ?: error("product.json must contain an object")
            val updates = product["updates"] as? Map<*, *> ?: error("product.updates must be an object")
            val productName = product.text("name")
            val applicationId = product.text("applicationId")
            val scheme = product.text("deepLinkScheme")
            val rawCode = product["versionCode"] as? Number ?: error("product.versionCode must be an integer")
            val longCode = rawCode.toLong()
            require(longCode in 1..2_100_000_000L && rawCode.toDouble() == longCode.toDouble()) {
                "product.versionCode must be an integer in 1..2100000000"
            }
            val versionCode = longCode.toInt()
            val versionName = product.text("versionName")
            val certificate = product.text("signingCertificateSha256")
            val android = project.extensions.getByType<ApplicationExtension>()
            val components = project.extensions.getByType<ApplicationAndroidComponentsExtension>()

            // Finalize after upstream defaults: product identity must not be overwritten later.
            components.finalizeDsl { extension ->
                extension.buildFeatures.resValues = true
                extension.defaultConfig.apply {
                    this.applicationId = applicationId
                    this.versionCode = versionCode
                    this.versionName = versionName
                    resValue("string", "host_product_name", productName)
                    buildConfigField("String", "HOST_PRODUCT_NAME", JsonOutput.toJson(productName))
                    buildConfigField(
                        "String",
                        "HOST_UPDATE_REPOSITORY",
                        JsonOutput.toJson(updates.text("repository"))
                    )
                    buildConfigField(
                        "String",
                        "HOST_UPDATE_INDEX_ASSET",
                        JsonOutput.toJson(updates.text("assetName"))
                    )
                    buildConfigField("String", "HOST_APK_CERTIFICATE_SHA256", JsonOutput.toJson(certificate))
                }
                extension.buildTypes.forEach { buildType ->
                    val variantScheme = if (buildType.name == "debug") "$scheme-debug" else scheme
                    buildType.manifestPlaceholders["hostDeepLinkScheme"] = variantScheme
                    buildType.buildConfigField("String", "HOST_DEEP_LINK_SCHEME", JsonOutput.toJson(variantScheme))
                    buildType.buildConfigField("String", "VERSION_NAME", JsonOutput.toJson(versionName))
                    buildType.buildConfigField("String", "VERSION_CODE", JsonOutput.toJson(versionCode.toString()))
                }
                extension.buildTypes.getByName("release").signingConfig = null
            }

            components.onVariants(components.selector().all()) { variant ->
                val generated = project.tasks.register<GenerateHostShortcuts>(
                    "generate${variant.name.replaceFirstChar { it.uppercase() }}HostShortcuts"
                ) {
                    targetPackage.set(variant.applicationId)
                    sourceNamespace.set(android.namespace!!)
                    deepLinkScheme.set(if (variant.buildType == "debug") "$scheme-debug" else scheme)
                    outputDirectory.set(project.layout.buildDirectory.dir("generated/pluginHost/${variant.name}/res"))
                }
                variant.sources.res?.addGeneratedSourceDirectory(generated, GenerateHostShortcuts::outputDirectory)
            }
        }
    }
}

private fun Map<*, *>.text(key: String): String = this[key] as? String
    ?: error("product.$key must be a string")

/** Static shortcuts need the actual variant package; namespace is only their class prefix. */
abstract class GenerateHostShortcuts : DefaultTask() {
    @get:Input abstract val targetPackage: Property<String>

    @get:Input abstract val sourceNamespace: Property<String>

    @get:Input abstract val deepLinkScheme: Property<String>

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkg = targetPackage.get()
        val namespace = sourceNamespace.get()
        val xml = outputDirectory.file("xml/host_shortcuts.xml").get().asFile
        xml.parentFile.mkdirs()
        xml.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
                <shortcut android:shortcutId="camera" android:enabled="true"
                    android:icon="@drawable/ic_lucide_camera"
                    android:shortcutShortLabel="@string/shortcut_take_picture"
                    android:shortcutLongLabel="@string/shortcut_take_picture">
                    <intent android:action="android.intent.action.VIEW"
                        android:targetPackage="$pkg"
                        android:targetClass="$namespace.ui.activity.ShortcutHandlerActivity"
                        android:data="${deepLinkScheme.get()}://shortcut" />
                    <categories android:name="android.shortcut.conversation" />
                </shortcut>
                <shortcut android:shortcutId="translator" android:enabled="true"
                    android:icon="@drawable/ic_translate"
                    android:shortcutShortLabel="@string/translator_page_title"
                    android:shortcutLongLabel="@string/translator_page_title">
                    <intent android:action="$pkg.action.TRANSLATE" android:targetPackage="$pkg"
                        android:targetClass="$namespace.RouteActivity" />
                </shortcut>
                <shortcut android:shortcutId="media_creation" android:enabled="true"
                    android:icon="@drawable/ic_media_creation"
                    android:shortcutShortLabel="@string/shortcut_media_creation"
                    android:shortcutLongLabel="@string/shortcut_media_creation">
                    <intent android:action="$pkg.action.MEDIA_CREATION" android:targetPackage="$pkg"
                        android:targetClass="$namespace.RouteActivity" />
                </shortcut>
            </shortcuts>
        """.trimIndent() + "\n")
    }
}
