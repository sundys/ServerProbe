package com.serverprobe.manager.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 显示模式：跟随系统 / 日间 / 夜间 */
enum class DisplayMode { SYSTEM, LIGHT, DARK }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DISPLAY_MODE = intPreferencesKey("display_mode")
        val POLL_INTERVAL = intPreferencesKey("poll_interval_sec")
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
        val TERM_FONT_SIZE = floatPreferencesKey("term_font_size")
    }

    val displayMode: Flow<DisplayMode> = context.dataStore.data.map { p ->
        when (p[Keys.DISPLAY_MODE] ?: 0) {
            1 -> DisplayMode.LIGHT
            2 -> DisplayMode.DARK
            else -> DisplayMode.SYSTEM
        }
    }

    /** 状态轮询间隔（秒），默认 15 */
    val pollIntervalSec: Flow<Int> = context.dataStore.data.map { p -> (p[Keys.POLL_INTERVAL] ?: 15).coerceIn(5, 300) }

    /** 应用切后台后启用生物识别/PIN 锁 */
    val biometricLock: Flow<Boolean> = context.dataStore.data.map { p -> p[Keys.BIOMETRIC_LOCK] ?: false }

    /** 终端字号（sp），默认 13 */
    val terminalFontSize: Flow<Float> = context.dataStore.data.map { p -> p[Keys.TERM_FONT_SIZE] ?: 13f }

    suspend fun setDisplayMode(mode: DisplayMode) {
        context.dataStore.edit { it[Keys.DISPLAY_MODE] = mode.ordinal }
    }

    suspend fun setPollIntervalSec(sec: Int) {
        context.dataStore.edit { it[Keys.POLL_INTERVAL] = sec.coerceIn(5, 300) }
    }

    suspend fun setBiometricLock(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BIOMETRIC_LOCK] = enabled }
    }

    suspend fun setTerminalFontSize(sp: Float) {
        context.dataStore.edit { it[Keys.TERM_FONT_SIZE] = sp.coerceIn(8f, 28f) }
    }
}
