import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Provider customization is local build configuration. Public-source builds
// intentionally omit provider-customization.properties and compile the
// affected integration as an explicit disabled capability. Private/production
// builds opt into strict validation with -PproviderCustomizationRequired=true.
val providerCustomizationFile = rootProject.file("app/provider-customization.properties")

private fun readU16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun readU32(bytes: ByteArray, offset: Int): Long =
    (0 until 4).fold(0L) { value, index ->
        value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
    }

private fun readU64(bytes: ByteArray, offset: Int): Long =
    (0 until 8).fold(0L) { value, index ->
        value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
    }

private fun loadAlignments(elf: ByteArray): List<Long> {
    require(elf.size >= 16 && elf.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) {
        "not an ELF file"
    }
    require(elf[5].toInt() == 1) { "only little-endian ELF files are supported" }
    val elfClass = elf[4].toInt()
    val phoff: Long
    val phentsize: Int
    val phnum: Int
    val alignOffset: Int
    val alignSize: Int
    when (elfClass) {
        1 -> {
            phoff = readU32(elf, 28)
            phentsize = readU16(elf, 42)
            phnum = readU16(elf, 44)
            alignOffset = 28
            alignSize = 4
        }
        2 -> {
            phoff = readU64(elf, 32)
            phentsize = readU16(elf, 54)
            phnum = readU16(elf, 56)
            alignOffset = 48
            alignSize = 8
        }
        else -> error("unsupported ELF class: $elfClass")
    }
    require(phentsize >= alignOffset + alignSize) { "invalid program-header size" }
    return buildList {
        repeat(phnum) { index ->
            val entry = Math.addExact(phoff, Math.multiplyExact(index.toLong(), phentsize.toLong())).toInt()
            require(entry >= 0 && entry + phentsize <= elf.size) { "truncated program headers" }
            if (readU32(elf, entry) == 1L) {
                add(if (alignSize == 8) readU64(elf, entry + alignOffset) else readU32(elf, entry + alignOffset))
            }
        }
    }.also { require(it.isNotEmpty()) { "ELF has no PT_LOAD segments" } }
}

val verifyRcloneAar16k by tasks.registering {
    group = "verification"
    description = "Reject stale rclone AARs without 16 KB ELF alignment and both Android ABIs."
    doLast {
        val aar = layout.projectDirectory.file("libs/rclone.aar").asFile
        if (!aar.isFile) {
            throw GradleException(
                "Missing ${aar.name}. Run deps/build_rclone_android.sh and copy deps/build/rclone/rclone.aar to app/libs."
            )
        }
        val failures = mutableListOf<String>()
        ZipFile(aar).use { archive ->
            for (abi in listOf("arm64-v8a", "x86_64")) {
                val path = "jni/$abi/libgojni.so"
                val entry = archive.getEntry(path)
                if (entry == null) {
                    failures += "$path is missing"
                    continue
                }
                try {
                    val alignments = loadAlignments(archive.getInputStream(entry).use { it.readBytes() })
                    if (alignments.any { it < 0x4000L || it % 0x4000L != 0L }) {
                        failures += "$path LOAD alignments=${alignments.joinToString(",") { "0x${it.toString(16)}" }}"
                    }
                } catch (error: Exception) {
                    failures += "$path: ${error.message ?: error::class.simpleName}"
                }
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException(
                "rclone.aar failed the Android 16 KB native check:\n" +
                    failures.joinToString("\n") { "- $it" } +
                    "\nRegenerate it with deps/build_rclone_android.sh before building."
            )
        }
    }
}

val providerCustomizationRequired = providers.gradleProperty("providerCustomizationRequired")
    .orNull
    ?.trim()
    ?.lowercase()
    ?.let { raw ->
        when (raw) {
            "true" -> true
            "false" -> false
            else -> throw GradleException(
                "providerCustomizationRequired must be either true or false."
            )
        }
    }
    ?: false

val appCustomization = Properties().apply {
    if (providerCustomizationFile.isFile) {
        providerCustomizationFile.inputStream().use { load(it) }
    }
}

val providerCustomizationUnavailable = "NOT_AVAILABLE_IN_THIS_BUILD"
val anthropicOAuthIdentifierPrompt = appCustomization
    .getProperty("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

if (providerCustomizationRequired && !providerCustomizationFile.isFile) {
    throw GradleException(
        "provider-customization.properties is required when " +
            "-PproviderCustomizationRequired=true."
    )
}
if (providerCustomizationRequired && anthropicOAuthIdentifierPrompt == null) {
    throw GradleException(
        "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT must be present and non-blank when " +
            "-PproviderCustomizationRequired=true."
    )
}

val claudeOAuthCustomizationAvailable = anthropicOAuthIdentifierPrompt != null
val compiledAnthropicOAuthIdentifierPrompt =
    anthropicOAuthIdentifierPrompt ?: providerCustomizationUnavailable

fun buildConfigString(value: String): String =
    "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\""

val runtimeDistDir = rootProject.file("../../dist")
val packagedRootfs = runtimeDistDir.resolve("ubuntu-arm64-rootfs.tar.gz")
val packagedRuntimeManifest = runtimeDistDir.resolve("runtime-manifest.json")
val packagedRootNetworkProxy = runtimeDistDir.resolve("minis-root-network-proxy-arm64-v8a")
val packagedRootNetworkProxySha = runtimeDistDir.resolve("minis-root-network-proxy-arm64-v8a.sha256")
val generatedRuntimeAssets = layout.buildDirectory.dir("generated/runtimePayload/assets")
val generatedRootNetworkProxyJniLibs = layout.buildDirectory.dir("generated/rootNetworkProxy/jniLibs")
val runtimeRootfsAsset = "minis-runtime/ubuntu-arm64-rootfs.tar.gz"
val protectedRuntimeRootfsAsset = "$runtimeRootfsAsset.aapt-preserve"

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

android {
    namespace = "com.openminis.app"
    ndkVersion = providers.environmentVariable("MINIS_NDK_VERSION")
        .getOrElse("28.2.13676358")
    // Compile against Android 16 APIs used by the Live Updates path. targetSdk
    // remains 35; Android 16-only behavior is runtime-gated by SDK level.
    compileSdk = 36

    defaultConfig {
        applicationId = "llc.slacker.minis"
        minSdk = 26
        targetSdk = 35
        versionCode = 39
        versionName = "1.01-beta.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Claude OAuth is either explicitly available with a configured prompt,
        // or explicitly disabled. Never compile an empty-string third state.
        buildConfigField(
            "boolean",
            "CLAUDE_OAUTH_CUSTOMIZATION_AVAILABLE",
            claudeOAuthCustomizationAvailable.toString()
        )
        buildConfigField(
            "String",
            "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT",
            buildConfigString(compiledAnthropicOAuthIdentifierPrompt)
        )

        ndk {
            // x86_64 supports emulator development; arm64-v8a is the primary
            // physical-device ABI.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
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

    sourceSets.getByName("main") {
        assets.srcDir(generatedRuntimeAssets)
        jniLibs.srcDir(generatedRootNetworkProxyJniLibs)
    }

    packaging {
        jniLibs {
            // The single-purpose Root network proxy executes directly from
            // ApplicationInfo.nativeLibraryDir, so the ELF must be materialized.
            useLegacyPackaging = true
        }
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
// Deterministic, non-secret diagnostic used by CI to exercise public,
// required-missing, and configured provider-customization build paths. Never
// print the configured customization value itself.
tasks.register("printProviderCustomizationCapability") {
    doLast {
        println("CLAUDE_OAUTH_CUSTOMIZATION_AVAILABLE=$claudeOAuthCustomizationAvailable")
        println(
            "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT_STATE=" +
                if (claudeOAuthCustomizationAvailable) "CONFIGURED" else providerCustomizationUnavailable
        )
    }
}

val copyBashismRules by tasks.registering(Copy::class) {
    from(rootProject.file("../shared/bashism")) {
        include("bashism_rules.json", "bashism_test_vectors.json")
    }
    into(layout.projectDirectory.dir("src/main/assets/bashism"))
}
val stageRuntimePayload by tasks.registering {
    group = "build"
    description = "Stages an optional verified rootfs-only runtime payload from dist into Android assets."
    inputs.files(packagedRootfs, packagedRuntimeManifest)
    outputs.dir(generatedRuntimeAssets)
    outputs.upToDateWhen { false }
    doLast {
        val inputs = listOf(packagedRootfs, packagedRuntimeManifest)
        val missing = inputs.filterNot { it.isFile }
        val required = System.getenv("MINIS_REQUIRE_RUNTIME_PAYLOAD") == "1"
        if ((missing.isNotEmpty() && missing.size != inputs.size) || (required && missing.isNotEmpty())) {
            throw GradleException("Rootfs runtime payload is incomplete; missing: ${missing.joinToString { it.path }}")
        }

        delete(generatedRuntimeAssets)
        if (missing.isNotEmpty()) return@doLast

        val manifest = JsonSlurper().parse(packagedRuntimeManifest) as? Map<*, *>
            ?: throw GradleException("Runtime manifest is not a JSON object")
        val expected = mapOf(
            "schemaVersion" to 3,
            "distro" to "ubuntu",
            "arch" to "arm64",
            "profile" to "base",
        )
        expected.forEach { (key, value) ->
            if (manifest[key] != value) throw GradleException("Runtime manifest $key must be $value")
        }
        if (manifest.keys.any { it in setOf("minisdVersion", "minisdSha256", "protocolVersion") }) {
            throw GradleException("Runtime manifest contains obsolete broker fields")
        }
        if (manifest["rootfsSha256"] != sha256(packagedRootfs)) {
            throw GradleException("Runtime manifest rootfs SHA-256 mismatch")
        }
        if (manifest["requiredCommands"] != listOf("python3", "git", "curl")) {
            throw GradleException("Runtime manifest requiredCommands mismatch")
        }

        copy {
            from(packagedRootfs) { rename { "ubuntu-arm64-rootfs.tar.gz.aapt-preserve" } }
            from(packagedRuntimeManifest)
            into(generatedRuntimeAssets.get().dir("minis-runtime"))
        }
    }
}

val stageRootNetworkProxy by tasks.registering {
    group = "build"
    description = "Stages the single-purpose Root outbound proxy separately from the rootfs payload."
    inputs.files(packagedRootNetworkProxy, packagedRootNetworkProxySha)
    outputs.dir(generatedRootNetworkProxyJniLibs)
    outputs.upToDateWhen { false }
    doLast {
        val inputs = listOf(packagedRootNetworkProxy, packagedRootNetworkProxySha)
        val missing = inputs.filterNot { it.isFile }
        val required = System.getenv("MINIS_REQUIRE_RUNTIME_PAYLOAD") == "1"
        val runtimePresent = packagedRootfs.isFile || packagedRuntimeManifest.isFile
        if ((missing.isNotEmpty() && missing.size != inputs.size) || ((required || runtimePresent) && missing.isNotEmpty())) {
            throw GradleException("Root network proxy payload is incomplete; missing: ${missing.joinToString { it.path }}")
        }

        delete(generatedRootNetworkProxyJniLibs)
        if (missing.isNotEmpty()) return@doLast
        val expected = packagedRootNetworkProxySha.readText().trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty().lowercase()
        if (!expected.matches(Regex("[0-9a-f]{64}")) || expected != sha256(packagedRootNetworkProxy)) {
            throw GradleException("Root network proxy SHA-256 mismatch")
        }
        copy {
            from(packagedRootNetworkProxy)
            into(generatedRootNetworkProxyJniLibs.get().dir("arm64-v8a"))
            rename { "libminisnetproxy.so" }
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyBashismRules); dependsOn(stageRuntimePayload) }
tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }
    .configureEach {
        // AAPT strips a terminal .gz suffix even when noCompress is set. Keep
        // an internal suffix through merge, then restore the runtime contract
        // path in the build output before APK packaging consumes it.
        doLast {
            val protected = outputs.files.files.asSequence()
                .filter { it.isDirectory }
                .map { it.resolve(protectedRuntimeRootfsAsset) }
                .filter { it.isFile }
                .toList()
            if (protected.size > 1) {
                throw GradleException("Multiple merged runtime rootfs assets found: $protected")
            }
            protected.singleOrNull()?.let { source ->
                val target = source.parentFile.resolve("ubuntu-arm64-rootfs.tar.gz")
                if (target.exists()) {
                    throw GradleException("Merged runtime rootfs target already exists: $target")
                }
                Files.move(source.toPath(), target.toPath())
            }
        }
    }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(stageRootNetworkProxy) }
tasks.named("preBuild") {
    dependsOn(copyBashismRules)
    dependsOn(stageRuntimePayload)
    dependsOn(stageRootNetworkProxy)
    dependsOn(verifyRcloneAar16k)
}

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
    if (System.getProperty("os.name").startsWith("Windows")) {
        val gitBash = file("C:/Program Files/Git/bin/bash.exe")
        if (gitBash.isFile) {
            commandLine(gitBash.absolutePath, script.absolutePath.replace('\\', '/'))
        } else {
            val windowsPath = script.absolutePath.replace('\\', '/')
            val wslPath = "/mnt/${windowsPath.substringBefore(':').lowercase()}${windowsPath.substringAfter(':')}"
            commandLine("wsl.exe", "-e", "bash", wslPath)
        }
    } else {
        commandLine("bash", script.absolutePath)
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") && it.name.contains("Debug") }
    .configureEach { dependsOn(stageDebugSkillAssets) }
// Debug lint models read the generated source-set assets directly rather than
// going through mergeDebugAssets, so declare the producer explicitly. Without
// this edge, Gradle 8 rejects combined lint + assemble invocations as unsafe.
tasks.matching { it.name.contains("Debug") && it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(stageDebugSkillAssets) }

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.2.0")
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

    // rclone, via the upstream gomobile binding, for backup destinations.
    // The AAR is built in CI from deps/rclone-mobile before Gradle runs.
    implementation(group = "", name = "rclone", ext = "aar")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coil (image loading & GIF playback)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

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
