package com.serverprobe.manager.remote

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * 扫码页：复用 zxing 的 CaptureActivity 扫码逻辑，只改清单里的方向与主题。
 * 库自带的 CaptureActivity 在清单中锁死 sensorLandscape，竖屏手持扫码很别扭；
 * 这里用 `fullSensor` + `setOrientationLocked(false)` 让取景框跟随设备方向。
 */
class QrScanActivity : CaptureActivity()
