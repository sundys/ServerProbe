package com.serverprobe.manager.ssh

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.serverprobe.manager.App
import com.serverprobe.manager.Diagnostics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SSH 会话的「切后台保活」。
 *
 * **为什么需要它**：App 没有可见界面、也没在跑前台服务时，进程会落到 cached
 * 状态。Android 12+ 的 cached app freezer 会冻结该进程的**全部线程**（SSH 读
 * 线程停摆），国产 ROM 更激进——直接回收进程或收走网络。结果就是终端会话期间
 * 把 App 切到后台再切回来，连接已经断了。这不是本 App 的代码主动断开：会话住在
 * 存活于后台的 ViewModel 里，没有任何生命周期回调去关它。
 *
 * **策略**：有活跃会话 + App 处于后台时，拉起一个前台服务把进程从 cached 状态里
 * 摘出来（不被冻结/回收），同时开始 [BACKGROUND_GRACE_MS] 倒计时；期间回到前台
 * 立即撤销（连接原样保留），超时则主动断开会话，不长期占着服务器会话。
 *
 * 服务只在「有会话 且 在后台」时运行，所以正常使用终端时**不会**出现常驻通知。
 * 会话仍归 [com.serverprobe.manager.ui.ssh.TerminalViewModel] 所有，本对象只登记
 * 引用与超时回调，不持有通道、不参与读写。
 */
object SshSessionKeeper {

    /** 切到后台后仍保持连接的时长，超过即断开。 */
    const val BACKGROUND_GRACE_MS = 60_000L

    /** 与 [BACKGROUND_GRACE_MS] 保持一致的中文时长文案，供界面提示复用。 */
    val backgroundGraceText: String
        get() = if (BACKGROUND_GRACE_MS % 60_000L == 0L) {
            "${BACKGROUND_GRACE_MS / 60_000} 分钟"
        } else {
            "${BACKGROUND_GRACE_MS / 1000} 秒"
        }

    private class Tracked(val session: SshSession, val onExpired: () -> Unit)

    private val tracked = mutableSetOf<Tracked>()

    /** App 是否处于前台（至少有一个 Activity 已 started） */
    @Volatile
    private var foreground = true

    private var timer: Job? = null

    /**
     * 会话建立后登记；[onExpired] 在"后台超时被断开"时回调，用于把断开原因带到
     * 界面上。同一会话重复登记只保留一份。
     */
    @Synchronized
    fun attach(session: SshSession, onExpired: () -> Unit) {
        tracked.removeAll { it.session === session }
        tracked.add(Tracked(session, onExpired))
        // 正常路径是前台建立会话；后台期间建立（如返回 App 后自动重连）同样要计时
        if (!foreground) startGrace()
    }

    /** 会话结束（正常关闭 / 重连 / ViewModel 回收）后注销。 */
    @Synchronized
    fun detach(session: SshSession?) {
        if (session == null) return
        tracked.removeAll { it.session === session }
        if (tracked.isEmpty()) stopKeepAliveService()
    }

    /** 回到前台：取消倒计时并撤掉保活服务（终端正常使用时不留常驻通知）。 */
    @Synchronized
    fun onForeground() {
        foreground = true
        stopKeepAliveService()
    }

    /** 切到后台：有会话就开始 1 分钟倒计时 + 前台服务保活。 */
    @Synchronized
    fun onBackground() {
        foreground = false
        if (tracked.isNotEmpty()) startGrace()
    }

    /** 服务是否仍有存在意义（Service 自检用：进程被回收后重启时没有会话可保活）。 */
    @Synchronized
    fun shouldRun(): Boolean = !foreground && tracked.isNotEmpty()

    /**
     * App 当前是否在前台。仅供**诊断取证**：会话在"后台状态下"被 ViewModel 回收，
     * 说明不是系统冻结/回收进程，而是系统把 Activity 销毁了（如开发者选项"不保留
     * 活动"或个别 ROM 的激进策略），那种情况下前台服务救不了会话，需要另想办法。
     */
    @Synchronized
    fun isAppForeground(): Boolean = foreground

    /**
     * 注册到 Application 的 Activity 生命周期回调：把"App 是否在前台"换算成保活
     * 开关。用计数而非单个 Activity 的判断，是为了 RemoteActivity（WebView）等
     * 多 Activity 场景下不会误判成"已切到后台"。
     */
    fun activityLifecycleCallbacks(): Application.ActivityLifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1) onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                if (started > 0) started--
                if (started == 0) onBackground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    // ---------------------------------------------------------------- 内部实现

    private fun startGrace() {
        timer?.cancel()
        timer = App.instance.appScope.launch {
            delay(BACKGROUND_GRACE_MS)
            expire()
        }
        startKeepAliveService()
    }

    private fun expire() {
        val expired: List<Tracked>
        synchronized(this) {
            // 计时期间已回到前台（或有会话提前结束）→ 不倒计时断开
            if (foreground || tracked.isEmpty()) return
            expired = tracked.toList()
            tracked.clear()
        }
        stopKeepAliveService()
        expired.forEach { runCatching { it.onExpired() } }
    }

    private fun startKeepAliveService() {
        val ctx = App.instance
        // minSdk 26，startForegroundService 恒可用
        val intent = Intent(ctx, SshKeepAliveService::class.java)
        try {
            ctx.startForegroundService(intent)
        } catch (e: Exception) {
            // Android 12+ 禁止从后台启动前台服务，只有"刚从可见状态切走"的极短
            // 窗口被放行；个别 ROM 连这个窗口也不给。失败不致命——会话照常保留，
            // 只是这段后台时间可能被系统冻结，故落盘便于取证。
            Diagnostics.log(ctx, "keep-alive service start failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 取消倒计时并撤掉保活服务（回到前台 / 会话结束 / 后台超时都走这里）。 */
    // lint 的 ImplicitSamInstance 在这里是误报：stopService 按 Intent 里的
    // ComponentName 匹配，新建 Intent 同样能停掉服务，并非按对象身份比对。
    @Suppress("ImplicitSamInstance")
    private fun stopKeepAliveService() {
        timer?.cancel()
        timer = null
        val ctx = App.instance
        ctx.stopService(Intent(ctx, SshKeepAliveService::class.java))
    }
}
