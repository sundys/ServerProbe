package com.serverprobe.manager

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.serverprobe.manager.data.repo.DisplayMode
import com.serverprobe.manager.ui.AppNav
import com.serverprobe.manager.ui.theme.ServerProbeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    /**
     * 生物识别/系统凭据认证策略：仅在应用启动时进行一次。
     * 初始处于锁定态（避免内容闪现），判定未启用或验证通过后放行；
     * 切后台、系统文件选择器、修改配置等均不再触发认证。
     */
    private val locked = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = App.instance
            val mode by app.settings.displayMode.collectAsState(initial = DisplayMode.SYSTEM)
            val isLocked by locked
            ServerProbeTheme(mode) {
                if (isLocked) LockScreen(onRetry = { requestUnlock() }) else AppNav()
            }
        }
        lifecycleScope.launch {
            val enabled = App.instance.settings.biometricLock.first()
            if (enabled) requestUnlock() else locked.value = false
        }
    }

    /** 传统 startActivityForResult 通道（LegacyActivityResult）的结果转发。 */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        com.serverprobe.manager.ui.components.LegacyActivityResult.deliver(requestCode, resultCode, data)
    }

    private fun requestUnlock() {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val can = BiometricManager.from(this).canAuthenticate(allowed)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            locked.value = false // 设备无任何可用认证手段时不阻塞
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked.value = false
                }
                // 取消/失败保持锁定，可在锁定页点「重试解锁」再次发起
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁云枢")
            .setSubtitle("验证身份以访问服务器凭据")
            .setAllowedAuthenticators(allowed)
            .build()
        prompt.authenticate(info)
    }
}

@androidx.compose.runtime.Composable
private fun LockScreen(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("云枢", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "已锁定 · 验证身份后进入",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text("重试解锁") }
        }
    }
}
