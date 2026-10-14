plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// [T-android-voice-grokbot] Compose port of the GrokBot character, imported from
// Mr-XiaoLiang/GrokBot (MIT). Upstream ships it as its own module with `compileSdk 37` /
// Java 11; the module is pinned to this project's toolchain and namespace instead so the
// import stays a file-level copy. Source and license: PROVENANCE.md.
android {
    namespace = "com.lollipop.grokbot"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    api(composeBom)
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
}
