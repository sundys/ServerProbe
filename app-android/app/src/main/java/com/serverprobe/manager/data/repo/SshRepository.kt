package com.serverprobe.manager.data.repo

import com.serverprobe.manager.data.crypto.KeystoreCipher
import com.serverprobe.manager.data.db.AUTH_KEY
import com.serverprobe.manager.data.db.AUTH_PASSWORD
import com.serverprobe.manager.data.db.SshHostDao
import com.serverprobe.manager.data.db.SshHostEntity
import kotlinx.coroutines.flow.Flow

class SshRepository(private val dao: SshHostDao) {

    val hosts: Flow<List<SshHostEntity>> = dao.observeAll()

    suspend fun byId(id: Long): SshHostEntity? = dao.byId(id)

    suspend fun all(): List<SshHostEntity> = dao.all()

    /** 解密口令 */
    fun passwordOf(e: SshHostEntity): String? =
        e.passwordEnc?.takeIf { it.isNotEmpty() }?.let { runCatching { KeystoreCipher.decrypt(it) }.getOrNull() }

    /** 解密私钥文本（PEM / PPK） */
    fun privateKeyOf(e: SshHostEntity): String? =
        e.privateKeyEnc?.takeIf { it.isNotEmpty() }?.let { runCatching { KeystoreCipher.decrypt(it) }.getOrNull() }

    fun keyPassphraseOf(e: SshHostEntity): String? =
        e.keyPassphraseEnc?.takeIf { it.isNotEmpty() }?.let { runCatching { KeystoreCipher.decrypt(it) }.getOrNull() }

    /**
     * 新建或更新。凭据字段传空/null 表示保留原值（编辑时未重新输入）。
     */
    suspend fun save(
        id: Long?,
        alias: String,
        host: String,
        port: Int,
        username: String,
        authType: Int,
        password: String?,
        privateKey: String?,
        keyPassphrase: String?,
        hostKeyFingerprint: String? = null,
    ): Long {
        val old = id?.let { dao.byId(it) }
        val passwordEnc = when {
            authType == AUTH_PASSWORD && !password.isNullOrEmpty() -> KeystoreCipher.encrypt(password)
            else -> old?.passwordEnc
        }
        val keyEnc = when {
            authType == AUTH_KEY && !privateKey.isNullOrEmpty() -> KeystoreCipher.encrypt(privateKey)
            else -> old?.privateKeyEnc
        }
        val passEnc = when {
            !keyPassphrase.isNullOrEmpty() -> KeystoreCipher.encrypt(keyPassphrase)
            else -> old?.keyPassphraseEnc
        }
        val entity = SshHostEntity(
            id = id ?: 0,
            alias = alias.trim().ifEmpty { host },
            host = host.trim(),
            port = port,
            username = username.trim(),
            authType = authType,
            passwordEnc = passwordEnc,
            privateKeyEnc = keyEnc,
            keyPassphraseEnc = passEnc,
            hostKeyFingerprint = hostKeyFingerprint ?: old?.hostKeyFingerprint,
        )
        return dao.upsert(entity)
    }

    suspend fun save(e: SshHostEntity): Long = dao.upsert(e)

    suspend fun delete(e: SshHostEntity) = dao.delete(e)

    suspend fun findMatch(host: String, port: Int, username: String): SshHostEntity? =
        dao.findMatch(host, port, username)
}
