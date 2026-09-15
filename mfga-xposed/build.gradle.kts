plugins {
    // compileSdk/targetSdk = 37 (Android 17) 要求 AGP >= 9.1（AGP 9.3.0 官方兼容表
    // 明确写的是支持到 API 37），配套 Gradle 也要升到 9.5.0（见下面 CI workflow
    // 里的 `gradle wrapper --gradle-version`）。AGP 8.x 系列连 android-37 平台
    // 都识别不了，会在 sync/build 阶段直接报 "Failed to find Platform SDK"。
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}
