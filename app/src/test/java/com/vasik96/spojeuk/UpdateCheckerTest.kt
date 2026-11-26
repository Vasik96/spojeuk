package com.vasik96.spojeuk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private fun rel(v: String, published: Long = 2_000L) = AppRelease(v, "https://example.invalid", published)

    @Test
    fun comparesNumericParts() {
        assertTrue(UpdateChecker.compareVersions("6.0.10", "6.0.9") > 0)
        assertTrue(UpdateChecker.compareVersions("6.1", "6.0.4") > 0)
        assertTrue(UpdateChecker.compareVersions("5.9.9", "6.0.0") < 0)
        assertEquals(0, UpdateChecker.compareVersions("6.0", "6.0.0"))
        assertEquals(0, UpdateChecker.compareVersions("v6.0.4", "6.0.4"))
    }

    @Test
    fun notifiesOnlyForNewerReleasePublishedAfterBuild() {
        // instalace z GitHubu (sestaveno před vydáním novější verze)
        assertTrue(UpdateChecker.shouldNotify(rel("6.0.5", published = 2_000L), "6.0.4", buildTimeMs = 1_000L))
        // stejná verze
        assertFalse(UpdateChecker.shouldNotify(rel("6.0.4", published = 2_000L), "6.0.4", buildTimeMs = 1_000L))
        // vlastní testovací build sestavený po vydání (i se starším číslem verze) neotravuje
        assertFalse(UpdateChecker.shouldNotify(rel("6.0.5", published = 2_000L), "6.0.4", buildTimeMs = 3_000L))
    }

    @Test
    fun manualCheckIgnoresBuildTime() {
        assertTrue(UpdateChecker.isNewer(rel("6.0.5"), "6.0.4"))
        assertFalse(UpdateChecker.isNewer(rel("6.0.4"), "6.0.4"))
    }
}
