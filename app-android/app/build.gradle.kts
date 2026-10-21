// 注意：脚本作用域里的 `java` 指向 JavaPluginExtension，故 Base64 必须显式导入
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// 发布签名（4 个环境变量）
//   CI 由 GitHub Secrets 注入，本地也可按同样方式临时指定：
//     SIGNING_KEYSTORE_BASE64  keystore 文件的 Base64 内容
//     SIGNING_STORE_PASSWORD   keystore 库口令（store password）
//     SIGNING_KEY_ALIAS        密钥别名（key alias）
//     SIGNING_KEY_PASSWORD     密钥口令（key password）
//   四者齐全 → 解码后写入 build/signing/release.keystore 并用于签名（build/ 已被
//   .gitignore 忽略，不会入库）。
//   本机构建未设置环境变量时回退到 app/signing/serverprobe.keystore —— 该文件仅
//   保留在本机、已加入 .gitignore，不随仓库分发；CI 禁止回退，缺变量直接失败。
// ---------------------------------------------------------------------------
val signingEnvKeys = listOf(
    "SIGNING_KEYSTORE_BASE64",
    "SIGNING_STORE_PASSWORD",
    "SIGNING_KEY_ALIAS",
    "SIGNING_KEY_PASSWORD",
)
val signingEnv: Map<String, String> = signingEnvKeys.associateWith { System.getenv(it)?.trim().orEmpty() }
val hasEnvSigning: Boolean = signingEnv.values.none { it.isEmpty() }
val fallbackKeystore = file("signing/serverprobe.keystore")
// CI 只认 Secrets；本机 keystore 仅用于本地出包
val isCi: Boolean = !System.getenv("GITHUB_ACTIONS").isNullOrBlank()
val hasFallbackSigning: Boolean = !isCi && fallbackKeystore.exists()
val envKeystoreFile = layout.buildDirectory.file("signing/release.keystore").get().asFile

// 缺少任一签名变量时直接中止 CI 编译，而不是用错误密钥（或未签名）继续出包。
if (isCi && !hasEnvSigning) {
    val missing = signingEnv.filterValues { it.isEmpty() }.keys
    throw GradleException(
        "CI 构建要求签名环境变量齐全，当前缺失: ${missing.joinToString(", ")}。" +
            "请在仓库 Settings → Secrets and variables → Actions 中配置 SIGNING_*（见 README「发布签名」）。",
    )
}

if (hasEnvSigning) {
    // 兼容多行 Base64（去掉换行/空白后再解码）
    val base64 = signingEnv.getValue("SIGNING_KEYSTORE_BASE64").replace(Regex("\\s"), "")
    try {
        envKeystoreFile.parentFile?.mkdirs()
        envKeystoreFile.writeBytes(Base64.getDecoder().decode(base64))
    } catch (e: IllegalArgumentException) {
        throw GradleException("SIGNING_KEYSTORE_BASE64 不是合法的 Base64 内容：${e.message}")
    }
}

android {
    namespace = "com.serverprobe.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sundys.remoto"
        minSdk = 26
        targetSdk = 36
        // CI 通过 -PappVersion=<tag> 注入版本号；本地构建使用默认值
        versionCode = 38
        versionName = project.findProperty("appVersion") as String? ?: "1.1.29"
    }

    signingConfigs {
        create("release") {
            if (hasEnvSigning) {
                storeFile = envKeystoreFile
                storePassword = signingEnv.getValue("SIGNING_STORE_PASSWORD")
                keyAlias = signingEnv.getValue("SIGNING_KEY_ALIAS")
                keyPassword = signingEnv.getValue("SIGNING_KEY_PASSWORD")
            } else if (hasFallbackSigning) {
                storeFile = fallbackKeystore
                storePassword = "serverprobe123"
                keyAlias = "serverprobe"
                keyPassword = "serverprobe123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 无可用签名时留空，产物为 unsigned，避免给出误以为已签名的包
            signingConfig = if (hasEnvSigning || hasFallbackSigning) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 按 ABI 拆分产物：armv7 与 arm64 各一个 APK
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// 明确告知本次构建用的签名来源，便于在 CI 日志里一眼确认是否用上了环境变量
logger.lifecycle(
    when {
        hasEnvSigning -> "[signing] 使用环境变量注入的 keystore -> $envKeystoreFile"
        hasFallbackSigning -> "[signing] 使用本机 keystore（未入库）-> $fallbackKeystore"
        else -> "[signing] 未提供签名：release 产物将不带签名（请配置 SIGNING_* 环境变量）"
    }
)

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sshj)
    implementation(libs.eddsa)
    implementation(libs.biometric)
    testImplementation(libs.junit)
}
