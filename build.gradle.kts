plugins {
    // 8.9.1 是 androidx.core 1.17.0 的下限（其 AAR 元数据 minAndroidGradlePluginVersion），
    // 也是 compileSdk 36 的下限；升它就是为了倒计时通知能上灵动岛（DESIGN §3.9）。
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21" apply false
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
}
