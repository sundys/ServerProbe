package com.serverprobe.manager.ui.settings

/**
 * 第三方开源组件清单（按 release 包实际解析到的运行时依赖整理）。
 *
 * 许可证信息取自各组件随包的 Maven POM 与 LICENSE 文件：
 * - AndroidX / Compose / Kotlin / kotlinx / OkHttp / Okio / sshj / ZXing：Apache-2.0；
 * - BouncyCastle：Bouncy Castle Licence（MIT 风格）；
 * - eddsa：CC0-1.0；slf4j-api：MIT。
 *
 * 新增或升级依赖后请同步本清单（`./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 可核对）。
 */
object ThirdPartyLicenses {

    data class Library(
        val group: String,
        val name: String,
        val version: String,
        val license: String,
    ) {
        val coordinate: String get() = "$group:$name:$version"
    }

    /** 许可证官方地址，用于清单里的跳转 */
    val licenseUrls: Map<String, String> = mapOf(
        "Apache-2.0" to "https://www.apache.org/licenses/LICENSE-2.0",
        "MIT" to "https://opensource.org/license/mit",
        "CC0-1.0" to "https://creativecommons.org/publicdomain/zero/1.0/",
        "Bouncy Castle Licence" to "https://www.bouncycastle.org/licence.html",
    )

    val libraries: List<Library> = listOf(
        Library("androidx.activity", "activity", "1.10.1", "Apache-2.0"),
        Library("androidx.activity", "activity-compose", "1.10.1", "Apache-2.0"),
        Library("androidx.activity", "activity-ktx", "1.10.1", "Apache-2.0"),
        Library("androidx.annotation", "annotation-experimental", "1.4.1", "Apache-2.0"),
        Library("androidx.annotation", "annotation-jvm", "1.9.1", "Apache-2.0"),
        Library("androidx.appcompat", "appcompat", "1.2.0", "Apache-2.0"),
        Library("androidx.appcompat", "appcompat-resources", "1.2.0", "Apache-2.0"),
        Library("androidx.arch.core", "core-common", "2.2.0", "Apache-2.0"),
        Library("androidx.arch.core", "core-runtime", "2.2.0", "Apache-2.0"),
        Library("androidx.autofill", "autofill", "1.0.0", "Apache-2.0"),
        Library("androidx.biometric", "biometric", "1.1.0", "Apache-2.0"),
        Library("androidx.collection", "collection-jvm", "1.5.0", "Apache-2.0"),
        Library("androidx.collection", "collection-ktx", "1.5.0", "Apache-2.0"),
        Library("androidx.compose.animation", "animation-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.animation", "animation-core-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.foundation", "foundation-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.foundation", "foundation-layout-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.material", "material-icons-core-android", "1.7.8", "Apache-2.0"),
        Library("androidx.compose.material", "material-icons-extended-android", "1.7.8", "Apache-2.0"),
        Library("androidx.compose.material", "material-ripple-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.material3", "material3-android", "1.3.2", "Apache-2.0"),
        Library("androidx.compose.runtime", "runtime-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.runtime", "runtime-saveable-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.ui", "ui-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.ui", "ui-geometry-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.ui", "ui-graphics-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.ui", "ui-text-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.ui", "ui-tooling-preview-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.ui", "ui-unit-android", "1.8.2", "Apache-2.0"),
        Library("androidx.compose.ui", "ui-util-android", "1.8.2", "Apache-2.0"),
        Library("androidx.concurrent", "concurrent-futures", "1.1.0", "Apache-2.0"),
        Library("androidx.core", "core", "1.16.0", "Apache-2.0"),
        Library("androidx.core", "core-ktx", "1.16.0", "Apache-2.0"),
        Library("androidx.core", "core-viewtree", "1.0.0", "Apache-2.0"),
        Library("androidx.cursoradapter", "cursoradapter", "1.0.0", "Apache-2.0"),
        Library("androidx.customview", "customview", "1.0.0", "Apache-2.0"),
        Library("androidx.customview", "customview-poolingcontainer", "1.0.0", "Apache-2.0"),
        Library("androidx.datastore", "datastore-android", "1.1.1", "Apache-2.0"),
        Library("androidx.datastore", "datastore-core-android", "1.1.1", "Apache-2.0"),
        Library("androidx.datastore", "datastore-core-okio-jvm", "1.1.1", "Apache-2.0"),
        Library("androidx.datastore", "datastore-preferences-android", "1.1.1", "Apache-2.0"),
        Library("androidx.datastore", "datastore-preferences-core-jvm", "1.1.1", "Apache-2.0"),
        Library("androidx.drawerlayout", "drawerlayout", "1.0.0", "Apache-2.0"),
        Library("androidx.emoji2", "emoji2", "1.4.0", "Apache-2.0"),
        Library("androidx.fragment", "fragment", "1.8.9", "Apache-2.0"),
        Library("androidx.graphics", "graphics-path", "1.0.1", "Apache-2.0"),
        Library("androidx.interpolator", "interpolator", "1.0.0", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-common-java8", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-common-jvm", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-livedata", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-livedata-core", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-livedata-core-ktx", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-process", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-runtime-android", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-runtime-compose-android", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-runtime-ktx-android", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-viewmodel", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-viewmodel-android", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-viewmodel-compose-android", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-viewmodel-ktx", "2.9.1", "Apache-2.0"),
        Library("androidx.lifecycle", "lifecycle-viewmodel-savedstate-android", "2.9.1", "Apache-2.0"),
        Library("androidx.loader", "loader", "1.0.0", "Apache-2.0"),
        Library("androidx.navigation", "navigation-common-android", "2.9.0", "Apache-2.0"),
        Library("androidx.navigation", "navigation-compose-android", "2.9.0", "Apache-2.0"),
        Library("androidx.navigation", "navigation-runtime-android", "2.9.0", "Apache-2.0"),
        Library("androidx.profileinstaller", "profileinstaller", "1.4.0", "Apache-2.0"),
        Library("androidx.room", "room-common-jvm", "2.7.2", "Apache-2.0"),
        Library("androidx.room", "room-ktx", "2.7.2", "Apache-2.0"),
        Library("androidx.room", "room-runtime-android", "2.7.2", "Apache-2.0"),
        Library("androidx.savedstate", "savedstate-android", "1.3.0", "Apache-2.0"),
        Library("androidx.savedstate", "savedstate-compose-android", "1.3.0", "Apache-2.0"),
        Library("androidx.savedstate", "savedstate-ktx", "1.3.0", "Apache-2.0"),
        Library("androidx.sqlite", "sqlite-android", "2.5.1", "Apache-2.0"),
        Library("androidx.sqlite", "sqlite-framework-android", "2.5.1", "Apache-2.0"),
        Library("androidx.startup", "startup-runtime", "1.1.1", "Apache-2.0"),
        Library("androidx.tracing", "tracing", "1.2.0", "Apache-2.0"),
        Library("androidx.vectordrawable", "vectordrawable", "1.1.0", "Apache-2.0"),
        Library("androidx.vectordrawable", "vectordrawable-animated", "1.1.0", "Apache-2.0"),
        Library("androidx.versionedparcelable", "versionedparcelable", "1.1.1", "Apache-2.0"),
        Library("androidx.viewpager", "viewpager", "1.0.0", "Apache-2.0"),
        Library("com.google.guava", "listenablefuture", "1.0", "Apache-2.0"),
        Library("com.google.zxing", "core", "3.4.1", "Apache-2.0"),
        Library("com.hierynomus", "asn-one", "0.6.0", "Apache-2.0"),
        Library("com.hierynomus", "sshj", "0.38.0", "Apache-2.0"),
        Library("com.journeyapps", "zxing-android-embedded", "4.3.0", "Apache-2.0"),
        Library("com.squareup.okhttp3", "okhttp", "4.12.0", "Apache-2.0"),
        Library("com.squareup.okio", "okio-jvm", "3.6.0", "Apache-2.0"),
        Library("org.jetbrains", "annotations", "23.0.0", "Apache-2.0"),
        Library("org.jetbrains.kotlin", "kotlin-android-extensions-runtime", "1.9.22", "Apache-2.0"),
        Library("org.jetbrains.kotlin", "kotlin-parcelize-runtime", "1.9.22", "Apache-2.0"),
        Library("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.0", "Apache-2.0"),
        Library("org.jetbrains.kotlin", "kotlin-stdlib-jdk7", "1.9.10", "Apache-2.0"),
        Library("org.jetbrains.kotlin", "kotlin-stdlib-jdk8", "1.9.10", "Apache-2.0"),
        Library("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.10.2", "Apache-2.0"),
        Library("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2", "Apache-2.0"),
        Library("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.9.0", "Apache-2.0"),
        Library("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.9.0", "Apache-2.0"),
        Library("org.jspecify", "jspecify", "1.0.0", "Apache-2.0"),
        Library("org.bouncycastle", "bcpkix-jdk18on", "1.75", "Bouncy Castle Licence"),
        Library("org.bouncycastle", "bcprov-jdk18on", "1.75", "Bouncy Castle Licence"),
        Library("org.bouncycastle", "bcutil-jdk18on", "1.75", "Bouncy Castle Licence"),
        Library("net.i2p.crypto", "eddsa", "0.3.0", "CC0-1.0"),
        Library("org.slf4j", "slf4j-api", "2.0.7", "MIT"),
    )

    /** 按许可证分组，组内按 Maven 坐标排序 */
    fun grouped(): List<Pair<String, List<Library>>> =
        libraries.groupBy { it.license }
            .toList()
            .sortedBy { it.first }
}
