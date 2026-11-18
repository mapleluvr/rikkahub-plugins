package me.rerere.rikkahub.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Expectations come from the distribution fixture/runner, not from the implementation under test. */
class DistributionIdentityTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val expectedPackage get() = arguments.getString("expectedHostApplicationId")
        ?: "io.github.mapleluvr.rikkahub.debug"
    private val expectedScheme get() = arguments.getString("expectedHostScheme") ?: "rikkahub-plugins-debug"

    @Test
    @Suppress("DEPRECATION")
    fun installedPackageLabelVersionAndSourceNamespaceMatchDistribution() {
        val pkg = context.packageName
        assertEquals(expectedPackage, pkg)
        assertEquals(expectedPackage, BuildConfig.APPLICATION_ID)
        val info = context.packageManager.getPackageInfo(pkg, 0)
        assertEquals("RikkaHub Plugins", info.applicationInfo!!.loadLabel(context.packageManager).toString())
        assertEquals((arguments.getString("expectedHostVersionCode") ?: "1").toInt(), info.versionCode)
        assertEquals(arguments.getString("expectedHostVersionName") ?: "3.3.0-plugins.1", info.versionName)
        assertNotNull(Class.forName("me.rerere.rikkahub.RouteActivity"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun privateActionsAndDeepLinkResolveOnlyThroughDerivedIdentifiers() {
        val pm = context.packageManager
        for (action in listOf("TRANSLATE", "MEDIA_CREATION")) {
            val expected = pm.resolveActivity(
                Intent("$expectedPackage.action.$action").setPackage(expectedPackage),
                PackageManager.MATCH_DEFAULT_ONLY
            )
            assertEquals("me.rerere.rikkahub.RouteActivity", expected?.activityInfo?.name)
            assertNull(
                pm.resolveActivity(
                    Intent("me.rerere.rikkahub.action.$action").setPackage(expectedPackage),
                    PackageManager.MATCH_DEFAULT_ONLY
                )
            )
        }
        val camera = Intent(Intent.ACTION_VIEW, "$expectedScheme://shortcut".toUri()).setPackage(expectedPackage)
        assertEquals(
            "me.rerere.rikkahub.ui.activity.ShortcutHandlerActivity",
            pm.resolveActivity(camera, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name
        )
        val shared = Intent(Intent.ACTION_VIEW, "rikkahub://shortcut".toUri()).setPackage(expectedPackage)
        assertNull(pm.resolveActivity(shared, PackageManager.MATCH_DEFAULT_ONLY))
    }

    @Test
    @Suppress("DEPRECATION")
    fun fileAndDocumentProvidersUseTheRuntimePackage() {
        val pm = context.packageManager
        assertEquals(expectedPackage, pm.resolveContentProvider("$expectedPackage.fileprovider", 0)?.packageName)
        assertEquals(expectedPackage, pm.resolveContentProvider("$expectedPackage.documents", 0)?.packageName)
    }

    @Test
    fun staticShortcutsPinPackageClassActionAndCameraUri() {
        val shortcuts = context.getSystemService(ShortcutManager::class.java).manifestShortcuts.associateBy { it.id }
        assertEquals(setOf("camera", "translator", "media_creation"), shortcuts.keys)
        for ((id, shortcut) in shortcuts) {
            val intent = shortcut.intent!!
            assertEquals(expectedPackage, intent.component?.packageName)
            if (id == "camera") {
                assertEquals("me.rerere.rikkahub.ui.activity.ShortcutHandlerActivity", intent.component?.className)
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals("$expectedScheme://shortcut", intent.dataString)
            } else {
                assertEquals("me.rerere.rikkahub.RouteActivity", intent.component?.className)
                val action = if (id == "translator") "TRANSLATE" else "MEDIA_CREATION"
                assertEquals("$expectedPackage.action.$action", intent.action)
            }
        }
    }
}
