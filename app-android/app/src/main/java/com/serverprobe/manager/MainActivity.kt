package com.serverprobe.manager

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.serverprobe.manager.data.repo.DisplayMode
import com.serverprobe.manager.ui.AppNav
import com.serverprobe.manager.ui.theme.ServerProbeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settingsJob: Job? = null

    private var locked = mutableStateOf(false)
    private var biometricEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = App.instance
            val mode by app.settings.displayMode.collectAsState(initial = DisplayMode.SYSTEM)
            val isLocked by locked
            ServerProbeTheme(mode) {
                if (isLocked) {
                    androidx.compose.material3.Surface(
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    ) {}
                } else {
                    AppNav()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        settingsJob?.cancel()
        settingsJob = activityScope.launch {
            App.instance.settings.biometricLock.collectLatest { enabled ->
                biometricEnabled = enabled
                if (enabled && !locked.value) requestUnlock()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        settingsJob?.cancel()
        settingsJob = null
        if (biometricEnabled && !ForegroundGuard.shouldSkipLock()) locked.value = true
    }

    private fun requestUnlock() {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val can = BiometricManager.from(this).canAuthenticate(allowed)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            locked.value = false // 无可用认证手段时不阻塞
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked.value = false
                }
                // 取消/失败保持锁定，回到应用时再次提示
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁服务探针")
            .setSubtitle("验证身份以访问服务器凭据")
            .setAllowedAuthenticators(allowed)
            .build()
        prompt.authenticate(info)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
