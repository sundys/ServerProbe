package com.serverprobe.manager.data.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件加密：用户口令 PBKDF2-HMAC-SHA256(210000) 派生 AES-256 密钥 + GCM。
 * 与 Keystore 解耦，备份文件可跨设备恢复。
 */
object BackupCrypto {

    const val ITERATIONS = 210_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    class BackupCryptoException(message: String) : Exception(message)

    data class Envelope(val salt: ByteArray, val iv: ByteArray, val ciphertext: ByteArray)

    fun encrypt(plain: ByteArray, passphrase: CharArray): Envelope {
        require(passphrase.size >= 6) { "passphrase too short" }
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        return Envelope(salt, iv, cipher.doFinal(plain))
    }

    fun decrypt(envelope: Envelope, passphrase: CharArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(passphrase, envelope.salt),
                GCMParameterSpec(128, envelope.iv),
            )
            cipher.doFinal(envelope.ciphertext)
        } catch (e: Exception) {
            throw BackupCryptoException("口令错误或备份文件已损坏")
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
