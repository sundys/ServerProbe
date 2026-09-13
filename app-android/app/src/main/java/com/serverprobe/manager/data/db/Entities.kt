package com.serverprobe.manager.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 探针主机。敏感字段（tokenEnc）为 Keystore AES-256-GCM 加密后的 Base64。
 */
@Entity(tableName = "probe_hosts")
data class ProbeHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val tokenEnc: String,
    val useTls: Boolean = true,
    val fingerprint: String? = null,
    val allowInsecureTls: Boolean = false,
    val sshHostId: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * SSH 主机。passwordEnc/privateKeyEnc/keyPassphraseEnc 均为加密后的 Base64。
 * authType: 0=密码, 1=私钥
 */
@Entity(tableName = "ssh_hosts")
data class SshHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: Int = 0,
    val passwordEnc: String? = null,
    val privateKeyEnc: String? = null,
    val keyPassphraseEnc: String? = null,
    val hostKeyFingerprint: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

const val AUTH_PASSWORD = 0
const val AUTH_KEY = 1
