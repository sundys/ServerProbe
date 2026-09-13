package com.serverprobe.manager

/**
 * 前台连续性保护：应用内通过 SAF（系统文件选择器）等系统界面选取内容时，
 * 本 Activity 会短暂进入 onPause，但不应当触发生物识别锁（否则用户在选私钥时
 * 会被切到锁定页，表现为"App 自动退出前台"）。
 * 发起系统选择器前调用 [suppressLockOnce]，MainActivity.onPause 中消费该标记。
 */
object ForegroundGuard {

    @Volatile
    private var suppress = false

    fun suppressLockOnce() {
        suppress = true
    }

    /** 是否应跳过本次上锁；若标记有效则消费之。 */
    fun shouldSkipLock(): Boolean {
        if (suppress) {
            suppress = false
            return true
        }
        return false
    }
}
