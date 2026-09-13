package com.serverprobe.manager.ssh

import java.io.IOException
import java.io.StringReader
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource

/**
 * 多格式私钥加载器，按内容探测并依次尝试：
 *  - OpenSSH 新格式（BEGIN OPENSSH PRIVATE KEY，RSA/ECDSA/Ed25519）
 *  - PEM PKCS#1（BEGIN RSA/DSA/EC PRIVATE KEY）
 *  - PEM PKCS#8（BEGIN PRIVATE KEY / BEGIN ENCRYPTED PRIVATE KEY）
 *  - PuTTY PPK v2/v3（PuTTY-User-Key-File）
 * 支持加密私钥（需口令）。
 */
object SshKeyLoader {

    /** 探测密钥格式描述（用于 UI 展示） */
    fun detectFormat(content: String): String = when {
        content.contains("OPENSSH PRIVATE KEY") -> "OpenSSH 新格式"
        content.contains("PuTTY-User-Key-File") -> "PuTTY PPK"
        content.contains("BEGIN ENCRYPTED PRIVATE KEY") -> "PKCS#8 (加密)"
        content.contains("BEGIN PRIVATE KEY") -> "PKCS#8"
        content.contains("BEGIN RSA PRIVATE KEY") -> "PEM RSA (PKCS#1)"
        content.contains("BEGIN DSA PRIVATE KEY") -> "PEM DSA (PKCS#1)"
        content.contains("BEGIN EC PRIVATE KEY") -> "PEM EC (PKCS#1)"
        else -> "自动识别"
    }

    fun load(content: String, passphrase: String?): Result<FileKeyProvider> {
        val candidates: List<FileKeyProvider> = when {
            content.contains("PuTTY-User-Key-File") -> listOf(PuTTYKeyFile())
            content.contains("OPENSSH PRIVATE KEY") -> listOf(OpenSSHKeyFile(), PKCS8KeyFile())
            else -> listOf(PKCS8KeyFile(), OpenSSHKeyFile(), PuTTYKeyFile())
        }

        var lastError: Exception? = null
        for (provider in candidates) {
            try {
                if (passphrase != null) {
                    val pw = passphrase
                    provider.init(StringReader(content), object : PasswordFinder {
                        override fun reqPassword(resource: Resource<*>): CharArray = pw.toCharArray()
                        override fun shouldRetry(resource: Resource<*>): Boolean = false
                    })
                } else {
                    provider.init(StringReader(content))
                }
                if (provider.private != null) return Result.success(provider)
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result.failure(
            lastError ?: IOException("无法解析私钥（格式不受支持或缺少口令）"),
        )
    }
}
