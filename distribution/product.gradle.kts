import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

// hostctl validates the closed schema and pins this same blob before Gradle receives the source.
@Suppress("UNCHECKED_CAST")
val hostProduct = JsonSlurper().parseText(
    providers.fileContents(rootProject.layout.projectDirectory.file(
        "vendor/rikka-plugin-host/distribution/product.json"
    )).asText.get()
) as Map<String, Any>

@Suppress("UNCHECKED_CAST")
val hostUpdates = hostProduct.getValue("updates") as Map<String, String>
val hostName = hostProduct.getValue("name") as String
val hostApplicationId = hostProduct.getValue("applicationId") as String
val hostScheme = hostProduct.getValue("deepLinkScheme") as String
val hostVersionCode = (hostProduct.getValue("versionCode") as Number).toInt()
val hostVersionName = hostProduct.getValue("versionName") as String
val hostCertificate = hostProduct.getValue("signingCertificateSha256") as String

configure<ApplicationExtension> {
    defaultConfig {
        applicationId = hostApplicationId
        versionCode = hostVersionCode
        versionName = hostVersionName
        resValue("string", "host_product_name", hostName)
        buildConfigField("String", "HOST_PRODUCT_NAME", JsonOutput.toJson(hostName))
        buildConfigField("String", "HOST_UPDATE_REPOSITORY", JsonOutput.toJson(hostUpdates.getValue("repository")))
        buildConfigField("String", "HOST_UPDATE_INDEX_ASSET", JsonOutput.toJson(hostUpdates.getValue("assetName")))
        buildConfigField("String", "HOST_APK_CERTIFICATE_SHA256", JsonOutput.toJson(hostCertificate))
    }
    buildTypes.configureEach {
        val scheme = if (name == "debug") "$hostScheme-debug" else hostScheme
        manifestPlaceholders["hostDeepLinkScheme"] = scheme
        buildConfigField("String", "HOST_DEEP_LINK_SCHEME", JsonOutput.toJson(scheme))
        buildConfigField("String", "VERSION_NAME", JsonOutput.toJson(hostVersionName))
        buildConfigField("String", "VERSION_CODE", JsonOutput.toJson(hostVersionCode.toString()))
    }
    buildTypes.getByName("release").signingConfig = null
}

/** Static shortcuts need the actual variant package; namespace is only their class prefix. */
abstract class GenerateHostShortcuts : DefaultTask() {
    @get:Input abstract val targetPackage: Property<String>

    @get:Input abstract val sourceNamespace: Property<String>

    @get:Input abstract val scheme: Property<String>

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
                        android:data="${scheme.get()}://shortcut" />
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

configure<ApplicationAndroidComponentsExtension> {
    onVariants(selector().all()) { variant ->
        val generated = tasks.register<GenerateHostShortcuts>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}HostShortcuts"
        ) {
            targetPackage.set(variant.applicationId)
            sourceNamespace.set(project.extensions.getByType<ApplicationExtension>().namespace!!)
            scheme.set(if (variant.buildType == "debug") "$hostScheme-debug" else hostScheme)
            outputDirectory.set(project.layout.buildDirectory.dir("generated/pluginHost/${variant.name}/res"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(generated, GenerateHostShortcuts::outputDirectory)
    }
}
