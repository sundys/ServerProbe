package com.serverprobe.manager

import com.serverprobe.manager.data.crypto.BackupCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    @Test
    fun `roundtrip with correct passphrase`() {
        val plain = "机密数据 sensitive 报文 ✓ 123".toByteArray()
        val env = BackupCrypto.encrypt(plain, "正确口令123".toCharArray())
        val decrypted = BackupCrypto.decrypt(env, "正确口令123".toCharArray())
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun `wrong passphrase fails`() {
        val env = BackupCrypto.encrypt("test".toByteArray(), "password1".toCharArray())
        assertThrows(BackupCrypto.BackupCryptoException::class.java) {
            BackupCrypto.decrypt(env, "password2".toCharArray())
        }
    }

    @Test
    fun `same plaintext produces different ciphertext each time`() {
        val a = BackupCrypto.encrypt("same".toByteArray(), "password".toCharArray())
        val b = BackupCrypto.encrypt("same".toByteArray(), "password".toCharArray())
        assertTrue(!a.ciphertext.contentEquals(b.ciphertext))
        assertTrue(!a.iv.contentEquals(b.iv))
        assertTrue(!a.salt.contentEquals(b.salt))
    }

    @Test
    fun `short passphrase rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.encrypt("x".toByteArray(), "12345".toCharArray())
        }
    }

    @Test
    fun `iterations are strong enough`() {
        assertEquals(210_000, BackupCrypto.ITERATIONS)
    }
}
