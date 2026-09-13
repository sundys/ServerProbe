package com.serverprobe.manager

import android.app.Application
import android.content.Context
import com.serverprobe.manager.data.backup.BackupManager
import com.serverprobe.manager.data.db.AppDatabase
import com.serverprobe.manager.data.repo.ProbeRepository
import com.serverprobe.manager.data.repo.SettingsRepository
import com.serverprobe.manager.data.repo.SshRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 简易服务定位器：单 Activity + 轻量依赖，无需引入 Hilt。
 */
class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var probeRepo: ProbeRepository
        private set
    lateinit var sshRepo: SshRepository
        private set
    lateinit var backupManager: BackupManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.build(this)
        settings = SettingsRepository(this)
        sshRepo = SshRepository(db.sshHostDao())
        probeRepo = ProbeRepository(db.probeHostDao(), sshRepo, settings, appScope)
        backupManager = BackupManager(db, settings)
        probeRepo.start()
        installCrashHook()
    }

    /** 未捕获异常落盘（供设置页「复制诊断日志」取证），随后交还系统默认处理。 */
    private fun installCrashHook() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Diagnostics.crash(this, thread.name, android.util.Log.getStackTraceString(error))
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        lateinit var instance: App
            private set
        fun get(context: Context): App = context.applicationContext as App
    }
}
