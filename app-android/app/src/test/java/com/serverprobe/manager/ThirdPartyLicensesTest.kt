package com.serverprobe.manager

import com.serverprobe.manager.ui.settings.ThirdPartyLicenses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyLicensesTest {

    @Test
    fun `license list is not empty and every entry has a known license`() {
        assertTrue(ThirdPartyLicenses.libraries.isNotEmpty())
        ThirdPartyLicenses.libraries.forEach { lib ->
            assertTrue("${lib.coordinate} 缺少许可证", lib.license.isNotBlank())
            assertTrue("${lib.coordinate} 许可证未登记地址", lib.license in ThirdPartyLicenses.licenseUrls)
        }
    }

    @Test
    fun `no duplicate coordinates`() {
        val coords = ThirdPartyLicenses.libraries.map { it.coordinate }
        assertEquals(coords.size, coords.toSet().size)
    }

    @Test
    fun `grouped covers every library exactly once`() {
        val flat = ThirdPartyLicenses.grouped().flatMap { it.second }
        assertEquals(ThirdPartyLicenses.libraries.size, flat.size)
        assertEquals(
            ThirdPartyLicenses.libraries.map { it.coordinate }.toSet(),
            flat.map { it.coordinate }.toSet(),
        )
    }

    @Test
    fun `key third-party libraries are present`() {
        val coords = ThirdPartyLicenses.libraries.map { it.coordinate }.toSet()
        // 这些是项目直接依赖的关键组件，清单里必须有
        assertTrue(coords.any { it.startsWith("com.hierynomus:sshj:") })
        assertTrue(coords.any { it.startsWith("com.journeyapps:zxing-android-embedded:") })
        assertTrue(coords.any { it.startsWith("org.bouncycastle:bcprov") })
        assertTrue(coords.any { it.startsWith("net.i2p.crypto:eddsa:") })
        assertTrue(coords.any { it.startsWith("androidx.room:room-runtime") })
    }
}
