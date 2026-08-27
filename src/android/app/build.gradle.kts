import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Optional provider customization is local build configuration. The repository
// tracks only provider-customization.properties.example; the real
// provider-customization.properties file is gitignored. Features that require
// a missing private OAuth identifier must fail closed at their feature boundary,
// while unrelated API-key paths must remain usable.
val appCustomization = Properties().apply {
    val f = rootProject.file("app/provider-customization.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun customizationValue(key: String): String =
    (appCustomization.getProperty(key) ?: "").replace("\"", "\\\"")

android {
    namespace = "com.openminis.app"
    // Compile against Android 16 APIs used by the Live Updates path. targetSdk
    // remains 35; Android 16-only behavior is runtime-gated by SDK level.
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.openminispet.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 39
        versionName = "1.01-beta.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Optional Anthropic OAuth identifier prompt. Empty when the local
        // provider customization file does not configure it.
        buildConfigField(
            "String",
            "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT",
            "\"${customizationValue("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT")}\""
        )

        ndk {
            // x86_64 supports emulator development; arm64-v8a is the primary
            // physical-device ABI.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Production signing is configured centrally in the root Android
            // build.gradle.kts. Packaging a release without explicit RELEASE_*
            // credentials fails closed and never falls back to the debug key.
        }
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
        buildConfig = true
    }

    androidResources {
        // The Ubuntu rootfs is packaged as a compressed tar archive.
        noCompress += "tar.gz"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Existing debt snapshot only. New findings must fail CI; this file may
        // only shrink as historical findings are fixed.
        baseline = file("lint-baseline.xml")
    }
}

// Keep the shared bashism rule table and test vectors as a single source of
// truth under src/shared/bashism, copied into Android assets at build time.
val copyBashismRules by tasks.registering(Copy::class) {
    from(rootProject.file("../shared/bashism")) {
        include("bashism_rules.json", "bashism_test_vectors.json")
    }
    into(layout.projectDirectory.dir("src/main/assets/bashism"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyBashismRules) }
tasks.named("preBuild") { dependsOn(copyBashismRules) }

// Stage optional debug-server skill assets only for debug builds. Local
// development checkouts may provide the generator and .claude skill source;
// source-only checkouts build normally when those optional files are absent.
val stageDebugSkillAssets by tasks.registering(Exec::class) {
    val script = rootProject.file("../../scripts/gen_debug_skill_android.sh")
    val skillDir = rootProject.file("../../.claude/skills/debug-server")
    onlyIf { script.exists() }
    // Gradle validates declared inputs before onlyIf, so declare them only when
    // the optional local source actually exists.
    if (skillDir.isDirectory) inputs.dir(skillDir)
    if (script.isFile) inputs.file(script)
    outputs.dir(layout.projectDirectory.dir("src/debug/assets/debug-skill"))
    commandLine("bash", script.absolutePath)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") && it.name.contains("Debug") }
    .configureEach { dependsOn(stageDebugSkillAssets) }

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Vendored com.kyant.backdrop uses @Language("AGSL") from JetBrains annotations
    implementation("org.jetbrains:annotations:26.1.0")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner is used by XAIOAuthManager to detect Custom Tab dismissal.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Silero v5 VAD (ONNX Runtime + WebRTC APM).
    implementation("com.github.helloooideeeeea:RealTimeCutVADLibraryForAndroid:1.0.5@aar")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coil (image loading)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Markdown rendering
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.33.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.33.0")

    // Chrome Custom Tabs (in-app browser for OAuth)
    implementation("androidx.browser:browser:1.8.0")

    // WebViewAssetLoader serves pinned PWA HTML under
    // https://appassets.androidplatform.net/ without raw file:// access.
    implementation("androidx.webkit:webkit:1.12.1")

    // Drag-to-reorder for LazyColumn
    implementation("sh.calvin.reorderable:reorderable:2.4.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ACRA local crash report capture. acra-core has no HTTP sender.
    implementation("ch.acra:acra-core:5.12.0")

    // Shizuku-compatible privileged Android API bridge. AXManager/Sui-compatible
    // implementations reuse the same Shizuku protocol surface.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Testing — JVM unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20231013")

    // Testing — Instrumented (on-device) tests
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("junit:junit:4.13.2")
}
