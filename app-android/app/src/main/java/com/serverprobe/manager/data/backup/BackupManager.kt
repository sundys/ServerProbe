package com.serverprobe.manager.data.backup

import com.serverprobe.manager.data.crypto.BackupCrypto
import com.serverprobe.manager.data.db.AppDatabase
import com.serverprobe.manager.data.db.ProbeHostEntity
import com.serverprobe.manager.data.db.SshHostEntity
import com.serverprobe.manager.data.repo.SettingsRepository
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Base64

class BackupManager(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val linkStore: com.serverprobe.manager.remote.LinkStore,
) {

    @Serializable
    data class BackupRemoteLink(
        val id: String,
        val url: String,
        val name: String,
        val version: String = "",
        val host: String = "",
        val createdAt: Long = 0,
        val lastOpenedAt: Long = 0,
    )

    @Serializable
    data class BackupProbeHost(
        val name: String,
        val host: String,
        val port: Int,
        val token: String,
        val useTls: Boolean = true,
        val fingerprint: String? = null,
        val allowInsecureTls: Boolean = false,
        val sortOrder: Int = 0,
    )

    @Serializable
    data class BackupSshHost(
        val alias: String,
        val host: String,
        val port: Int = 22,
        val username: String,
        val authType: Int = 0,
        val password: String? = null,
        val privateKey: String? = null,
        val keyPassphrase: String? = null,
        val hostKeyFingerprint: String? = null,
    )

    @Serializable
    data class BackupSettings(
        val displayMode: Int = 0,
        val pollIntervalSec: Int = 15,
    )

    @Serializable
    data class BackupPayload(
        val format: String = FORMAT_PLAIN,
        val version: Int = 1,
        val created: Long = 0,
        val probeHosts: List<BackupProbeHost> = emptyList(),
        val sshHosts: List<BackupSshHost> = emptyList(),
        val remoteLinks: List<BackupRemoteLink> = emptyList(),
        val settings: BackupSettings = BackupSettings(),
    )

    @Serializable
    data class EncryptedFile(
        val format: String = FORMAT_ENVELOPE,
        val v: Int = 1,
        val kdf: String = "PBKDF2-SHA256",
        val iters: Int = BackupCrypto.ITERATIONS,
        val salt: String,
        val iv: String,
        val data: String,
    )

    data class ImportResult(val probes: Int, val sshHosts: Int, val links: Int = 0)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 导出：明文载荷 → 口令加密 → 信封 JSON 写出 */
    suspend fun export(output: OutputStream, passphrase: CharArray): Unit = withContext(Dispatchers.IO) {
        val probeHosts = db.probeHostDao().all().map {
            BackupProbeHost(
                name = it.name, host = it.host, port = it.port,
                token = com.serverprobe.manager.data.crypto.KeystoreCipher.decrypt(it.tokenEnc),
                useTls = it.useTls, fingerprint = it.fingerprint,
                allowInsecureTls = it.allowInsecureTls, sortOrder = it.sortOrder,
            )
        }
        val sshHosts = db.sshHostDao().all().map { e ->
            BackupSshHost(
                alias = e.alias, host = e.host, port = e.port, username = e.username,
                authType = e.authType,
                password = e.passwordEnc?.takeIf { p -> p.isNotEmpty() }
                    ?.let { com.serverprobe.manager.data.crypto.KeystoreCipher.decrypt(it) },
                privateKey = e.privateKeyEnc?.takeIf { p -> p.isNotEmpty() }
                    ?.let { com.serverprobe.manager.data.crypto.KeystoreCipher.decrypt(it) },
                keyPassphrase = e.keyPassphraseEnc?.takeIf { p -> p.isNotEmpty() }
                    ?.let { com.serverprobe.manager.data.crypto.KeystoreCipher.decrypt(it) },
                hostKeyFingerprint = e.hostKeyFingerprint,
            )
        }
        val payload = BackupPayload(
            created = System.currentTimeMillis(),
            probeHosts = probeHosts,
            sshHosts = sshHosts,
            remoteLinks = linkStore.load().map {
                BackupRemoteLink(
                    id = it.id, url = it.url, name = it.name,
                    version = it.version, host = it.host,
                    createdAt = it.createdAt, lastOpenedAt = it.lastOpenedAt,
                )
            },
            settings = BackupSettings(
                displayMode = when (settings.displayMode.first()) {
                    com.serverprobe.manager.data.repo.DisplayMode.LIGHT -> 1
                    com.serverprobe.manager.data.repo.DisplayMode.DARK -> 2
                    com.serverprobe.manager.data.repo.DisplayMode.SYSTEM -> 0
                },
                pollIntervalSec = settings.pollIntervalSec.first(),
            ),
        )
        val envelope = BackupCrypto.encrypt(json.encodeToString(payload).toByteArray(), passphrase)
        val file = EncryptedFile(
            salt = Base64.encodeToString(envelope.salt, Base64.NO_WRAP),
            iv = Base64.encodeToString(envelope.iv, Base64.NO_WRAP),
            data = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
        )
        output.use { it.write(json.encodeToString(file).toByteArray()) }
    }

    /** 恢复：读入信封 → 口令解密 → 按地址匹配合并（或清空后导入） */
    suspend fun import(
        input: InputStream,
        passphrase: CharArray,
        replaceAll: Boolean,
    ): ImportResult = withContext(Dispatchers.IO) {
        val text = input.use { it.readBytes().toString(Charsets.UTF_8) }
        val file = try {
            json.decodeFromString<EncryptedFile>(text)
        } catch (e: Exception) {
            throw BackupCrypto.BackupCryptoException("不是有效的备份文件")
        }
        if (file.kdf != "PBKDF2-SHA256") throw BackupCrypto.BackupCryptoException("不支持的加密方案: ${file.kdf}")
        val plain = BackupCrypto.decrypt(
            BackupCrypto.Envelope(
                salt = Base64.decode(file.salt, Base64.NO_WRAP),
                iv = Base64.decode(file.iv, Base64.NO_WRAP),
                ciphertext = Base64.decode(file.data, Base64.NO_WRAP),
            ),
            passphrase,
        )
        val payload = try {
            json.decodeFromString<BackupPayload>(plain.decodeToString())
        } catch (e: Exception) {
            throw BackupCrypto.BackupCryptoException("备份内容解析失败")
        }

        if (replaceAll) {
            db.probeHostDao().all().forEach { db.probeHostDao().delete(it) }
            db.sshHostDao().all().forEach { db.sshHostDao().delete(it) }
        }

        var sshCount = 0
        for (s in payload.sshHosts) {
            val existing = db.sshHostDao().findMatch(s.host, s.port, s.username)
            val entity = SshHostEntity(
                id = existing?.id ?: 0,
                alias = s.alias,
                host = s.host,
                port = s.port,
                username = s.username,
                authType = s.authType,
                passwordEnc = s.password?.let { com.serverprobe.manager.data.crypto.KeystoreCipher.encrypt(it) }
                    ?: existing?.passwordEnc,
                privateKeyEnc = s.privateKey?.let { com.serverprobe.manager.data.crypto.KeystoreCipher.encrypt(it) }
                    ?: existing?.privateKeyEnc,
                keyPassphraseEnc = s.keyPassphrase?.let { com.serverprobe.manager.data.crypto.KeystoreCipher.encrypt(it) }
                    ?: existing?.keyPassphraseEnc,
                hostKeyFingerprint = s.hostKeyFingerprint ?: existing?.hostKeyFingerprint,
            )
            db.sshHostDao().upsert(entity)
            sshCount++
        }

        var probeCount = 0
        for (p in payload.probeHosts) {
            val existing = db.probeHostDao().findByAddress(p.host, p.port)
            val entity = ProbeHostEntity(
                id = existing?.id ?: 0,
                name = p.name,
                host = p.host,
                port = p.port,
                tokenEnc = com.serverprobe.manager.data.crypto.KeystoreCipher.encrypt(p.token),
                useTls = p.useTls,
                fingerprint = p.fingerprint ?: existing?.fingerprint,
                allowInsecureTls = p.allowInsecureTls,
                sshHostId = existing?.sshHostId,
                sortOrder = p.sortOrder,
            )
            db.probeHostDao().upsert(entity)
            probeCount++
        }

        settings.setPollIntervalSec(payload.settings.pollIntervalSec)

        // 远程链接：按 id upsert（同 id 覆盖，其余保留）
        for (l in payload.remoteLinks) {
            linkStore.upsert(
                com.serverprobe.manager.remote.Link(
                    id = l.id,
                    url = l.url,
                    name = l.name,
                    version = l.version,
                    host = l.host,
                    createdAt = l.createdAt,
                    lastOpenedAt = l.lastOpenedAt,
                )
            )
        }

        ImportResult(probes = probeCount, sshHosts = sshCount, links = payload.remoteLinks.size)
    }

    companion object {
        const val FORMAT_PLAIN = "serverprobe-backup"
        const val FORMAT_ENVELOPE = "serverprobe-backup-encrypted"
    }
}
