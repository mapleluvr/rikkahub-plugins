package dev.rikkahub.plugins.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginVersionTest {
    @Test fun semanticOrderUsesNumericIdentifiersAndKeepsBuildMetadataOutOfPrecedence() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.2.0", "1.10.0", "99999999999999999999999.0.0")
        assertEquals(ordered, ordered.reversed().sortedBy { PluginVersion.parse(it) })
        assertEquals(0, PluginVersion.parse("1.0.0+a").compareTo(PluginVersion.parse("1.0.0+b")))
    }

    @Test fun malformedOrNoncanonicalVersionsAreRejectedBeforeSelection() {
        for (value in listOf("v1.0.0", "1.0", "01.0.0", "1.0.0-01", "1.0.0-a..b", "1.0.0+", "1.0.0\n")) {
            assertTrue(value, runCatching { PluginVersion.parse(value) }.isFailure)
        }
        assertTrue(PluginVersion.parse("1.0.0-rc.1+build.01").prerelease)
    }
}
