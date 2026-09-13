package com.serverprobe.manager

import com.serverprobe.manager.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun `version compare basic`() {
        assertEquals(0, UpdateManager.compareVersion("1.0.0", "1.0.0"))
        assertEquals(0, UpdateManager.compareVersion("v1.0.0", "1.0.0"))
        assertEquals(0, UpdateManager.compareVersion("1.0", "1.0.0"))
        assertTrue(UpdateManager.compareVersion("1.0.1", "1.0.0") > 0)
        assertTrue(UpdateManager.compareVersion("1.1.0", "1.0.9") > 0)
        assertTrue(UpdateManager.compareVersion("2.0", "1.99.99") > 0)
        assertTrue(UpdateManager.compareVersion("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun `version compare with suffixes and prefixes`() {
        assertTrue(UpdateManager.isNewer("v1.0.1", "1.0.0"))
        assertFalse(UpdateManager.isNewer("v1.0.0", "1.0.0"))
        assertFalse(UpdateManager.isNewer("v1.0.1-beta", "1.0.1")) // 后缀被忽略
        assertTrue(UpdateManager.isNewer("V1.2.0", "1.1.0"))
    }

    @Test
    fun `malformed versions treated as zero segments`() {
        assertEquals(-1, UpdateManager.compareVersion("1.0.x", "1.0.1"))
        assertEquals(0, UpdateManager.compareVersion("abc", "0"))
    }
}
