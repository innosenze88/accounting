package com.example

import com.example.data.update.AppVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun comparesReleaseTags() {
        assertTrue(AppVersion.isNewer("v1.2.0", "1.1.0"))
        assertTrue(AppVersion.isNewer("v1.10", "1.9.3"))
        assertTrue(AppVersion.isNewer("2", "1.9.9"))
        assertFalse(AppVersion.isNewer("v1.1", "1.1.0"))
        assertFalse(AppVersion.isNewer("v1.0.9", "1.1.0"))
        assertFalse(AppVersion.isNewer("v1.1.0-beta", "1.1.0"))
    }
}
