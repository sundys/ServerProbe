package com.serverprobe.manager.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.serverprobe.manager.Diagnostics
import com.serverprobe.manager.MainActivity
import com.serverprobe.manager.R

/**
 * SSH 会话的后台保活服务。
 *
 * 它**不持有会话**（会话仍在 [com.serverprobe.manager.ui.ssh.TerminalViewModel] 里，
 * 通道读写也仍由 `SshSession` 那条专用线程串行执行），存在的唯一目的是：只要处于
 * 「有活跃会话 + App 在后台」这段时间，就让进程一直有一个前台服务，从而不被
 * cached app freezer 冻结、也不被低内存回收——这正是"切后台再切回来 SSH 已断开"
 * 的外部原因（详见 [SshSessionKeeper]）。
 *
 * 由 [SshSessionKeeper] 启停：回到前台、会话结束、后台满 1 分钟都会立刻停掉它，
 * 因此终端正常使用时不会出现常驻通知。
 */
class SshKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 会话已经结束（前端撤销了保活，或进程被回收后系统重启服务）——没有可保活的
        // 对象，直接结束，避免留下空转的前台服务
        if (!SshSessionKeeper.shouldRun()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // missing type/permission 等配置问题：不能带着未 startForeground 的状态
            // 留着（系统会在 5 秒后判 ANR），记日志后自杀
            Diagnostics.log(this, "keep-alive startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
        }
        // 不粘性重启：进程被回收后会话已经不存在，重启服务没有意义
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SSH 会话保活", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "切到后台时短暂保持 SSH 连接"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify_ssh)
            .setContentTitle("SSH 连接保持中")
            .setContentText("切到后台 ${SshSessionKeeper.backgroundGraceText}内不会断开，超时自动断开")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "ssh_keep_alive"
        const val NOTIFICATION_ID = 1001
    }
}
