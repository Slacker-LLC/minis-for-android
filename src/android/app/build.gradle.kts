import java.util.Properties
import java.security.MessageDigest
import java.util.zip.ZipFile
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory

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
val appVersionName = "1.01-beta.2"
val appVersionCode = 39
fun customizationValue(key: String): String =
    (appCustomization.getProperty(key) ?: "").replace("\"", "\\\"")

val minisdNdkVersion = "27.0.12077973"
val androidSdkDir = System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let(::file)
    ?: System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let(::file)
    ?: file(System.getProperty("user.home")).resolve("AppData/Local/Android/Sdk")
val minisdNdkDir = androidSdkDir.resolve("ndk/$minisdNdkVersion")
val minisdHostIsWindows = System.getProperty("os.name").lowercase().contains("windows")
val minisdNdkHost = if (minisdHostIsWindows) "windows-x86_64" else "linux-x86_64"
val minisdClangName = if (minisdNdkHost.startsWith("windows")) {
    "aarch64-linux-android26-clang.cmd"
} else {
    "aarch64-linux-android26-clang"
}
val minisdReadelfName = "llvm-readelf" + if (minisdNdkHost.startsWith("windows")) ".exe" else ""
val minisdSourceDir = rootProject.file("../native/minisd")
val minisdGeneratedJniLibs = layout.buildDirectory.dir("generated/jniLibs")
val runtimeDistDir = rootProject.file("../../dist")
val runtimeRootfsBuilder = rootProject.file("../../scripts/build-ubuntu-rootfs.sh")
val runtimeRootfsArchive = runtimeDistDir.resolve("ubuntu-arm64-rootfs.tar.gz")
val runtimeRootfsChecksum = runtimeDistDir.resolve("ubuntu-arm64-rootfs.tar.gz.sha256")
val runtimeRootfsBuildManifest = runtimeDistDir.resolve("ubuntu-arm64-rootfs.manifest.json")

android {
    namespace = "io.github.slackerllc.minis"
    testNamespace = "io.github.slackerllc.minis.test"
    sourceSets.getByName("main").jniLibs.srcDir(minisdGeneratedJniLibs)
    // Compile against Android 16 APIs used by the Live Updates path. targetSdk
    // remains 35; Android 16-only behavior is runtime-gated by SDK level.
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.slackerllc.minis"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

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
        // The authoritative Ubuntu rootfs is packaged as one compressed asset.
        noCompress += "tar.gz"
    }

    packaging {
        jniLibs {
            // minisd is an executable ELF distributed through the APK native
            // library directory. Legacy packaging intentionally extracts the
            // file into ApplicationInfo.nativeLibraryDir so root can execve the
            // Package Manager-owned path; it is never copied to filesDir.
            useLegacyPackaging = true
            keepDebugSymbols += "**/libminisd.so"
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

val buildArm64Minisd by tasks.registering(Exec::class) {
    description = "Builds the APK-owned minisd for Android arm64-v8a."
    group = "build"
    workingDir(minisdSourceDir)
    val output = minisdSourceDir.resolve("target/aarch64-linux-android/release/minisd")
    inputs.dir(minisdSourceDir)
    outputs.file(output)
    doFirst {
        val clang = minisdNdkDir.resolve(
            "toolchains/llvm/prebuilt/$minisdNdkHost/bin/$minisdClangName",
        )
        if (!clang.isFile) {
            throw GradleException("Android NDK $minisdNdkVersion is missing: $clang")
        }
        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", clang.absolutePath)
        environment(
            "RUSTFLAGS",
            "-C relocation-model=pic -C link-arg=-pie -C link-arg=-Wl,-z,max-page-size=16384",
        )
    }
    commandLine("cargo", "build", "--locked", "--release", "--target", "aarch64-linux-android")
}

val verifyMinisdElf by tasks.registering {
    description = "Verifies the Android minisd ELF is an arm64 PIE executable."
    group = "verification"
    dependsOn(buildArm64Minisd)
    val binary = minisdSourceDir.resolve("target/aarch64-linux-android/release/minisd")
    inputs.file(binary)
    doLast {
        val readelf = minisdNdkDir.resolve(
            "toolchains/llvm/prebuilt/$minisdNdkHost/bin/$minisdReadelfName",
        )
        if (!readelf.isFile) throw GradleException("llvm-readelf is missing: $readelf")
        val process = ProcessBuilder(readelf.absolutePath, "-h", "-l", "-d", binary.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) throw GradleException("llvm-readelf failed:\n$output")
        check("ELF64" in output) { "minisd is not ELF64:\n$output" }
        check("AArch64" in output) { "minisd is not AArch64:\n$output" }
        check("Type:                              DYN" in output || "Elf file type is DYN" in output) {
            "minisd is not a PIE/ET_DYN executable:\n$output"
        }
        check("/system/bin/linker64" in output) { "minisd is not an Android executable:\n$output" }
        val loadSegments = output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("LOAD ") }
            .toList()
        check(loadSegments.isNotEmpty() && loadSegments.all { it.endsWith("0x4000") }) {
            "minisd LOAD segments are not 16 KB aligned:\n$output"
        }
        check("libc.so" in output && "libdl.so" in output) {
            "minisd does not expose the expected Android runtime dependencies:\n$output"
        }
        check("libglibc" !in output && "ld-linux" !in output) {
            "minisd unexpectedly depends on glibc:\n$output"
        }
    }
}

val packageMinisdNative by tasks.registering(Copy::class) {
    description = "Stages the verified minisd into the APK arm64 native library directory."
    group = "build"
    dependsOn(verifyMinisdElf)
    from(minisdSourceDir.resolve("target/aarch64-linux-android/release")) {
        include("minisd")
        rename { "libminisd.so" }
    }
    into(minisdGeneratedJniLibs.map { it.dir("arm64-v8a") })
}

val buildPinnedUbuntuRootfs by tasks.registering(Exec::class) {
    description = "Builds the pinned reproducible Ubuntu 24.04 arm64 runtime rootfs."
    group = "build"
    workingDir(rootProject.file("../.."))
    inputs.file(runtimeRootfsBuilder)
    outputs.file(runtimeRootfsArchive)
    outputs.file(runtimeRootfsChecksum)
    outputs.file(runtimeRootfsBuildManifest)
    commandLine("bash", runtimeRootfsBuilder.absolutePath)
}

fun sha256(file: java.io.File): String = file.inputStream().use(::sha256)

fun sha256(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun requireRuntimeString(map: Map<*, *>, key: String): String =
    (map[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("rootfs build manifest has no valid $key")

fun requireRuntimeInt(map: Map<*, *>, key: String): Int =
    (map[key] as? Number)?.toInt()?.takeIf { it > 0 }
        ?: throw GradleException("rootfs build manifest has no positive $key")

abstract class PackageRuntimeAssetsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val packageRuntimeAssets by tasks.registering(PackageRuntimeAssetsTask::class) {
    description = "Packages the reproducible Ubuntu rootfs and authoritative schema-v2 runtime manifest."
    group = "build"
    dependsOn(packageMinisdNative, buildPinnedUbuntuRootfs)
    val binary = minisdGeneratedJniLibs.map { it.file("arm64-v8a/libminisd.so") }
    inputs.file(binary)
    inputs.file(runtimeRootfsArchive)
    inputs.file(runtimeRootfsBuildManifest)
    doLast {
        val binaryFile = binary.get().asFile
        if (!runtimeRootfsArchive.isFile || !runtimeRootfsBuildManifest.isFile) {
            throw GradleException("Pinned rootfs Gradle producer did not create the required runtime outputs.")
        }
        @Suppress("UNCHECKED_CAST")
        val rootfs = JsonSlurper().parse(runtimeRootfsBuildManifest) as? Map<*, *>
            ?: throw GradleException("invalid rootfs build manifest")
        val rootfsSha = requireRuntimeString(rootfs, "sha256").lowercase()
        val actualRootfsSha = sha256(runtimeRootfsArchive)
        check(rootfsSha.matches(Regex("^[0-9a-f]{64}$"))) { "invalid rootfs SHA-256" }
        check(actualRootfsSha == rootfsSha) {
            "rootfs build output SHA mismatch: actual=$actualRootfsSha declared=$rootfsSha"
        }
        val rootfsVersion = requireRuntimeString(rootfs, "version")
        check(rootfsVersion.matches(Regex("^ubuntu-24\\.04-r[1-9][0-9]*-[0-9a-f]{16}$"))) {
            "invalid rootfsVersion: $rootfsVersion"
        }
        check(rootfsVersion.endsWith(rootfsSha.take(16))) {
            "rootfsVersion must be derived from final rootfs SHA-256"
        }
        val release = requireRuntimeString(rootfs, "release")
        check(release.startsWith("24.04")) { "unsupported rootfs release: $release" }
        val profile = requireRuntimeString(rootfs, "profile")
        check(profile == "base") { "unsupported rootfs profile: $profile" }
        val arch = requireRuntimeString(rootfs, "arch")
        check(arch == "arm64-v8a") { "unsupported rootfs ABI: $arch" }
        val upstream = requireRuntimeString(rootfs, "upstream_sha256").lowercase()
        check(upstream.matches(Regex("^[0-9a-f]{64}$"))) { "invalid rootfs upstream SHA-256" }
        val provisionRevision = requireRuntimeInt(rootfs, "provisionRevision")
        val commands = (rootfs["requiredCommands"] as? List<*>)
            ?.map { it as? String ?: throw GradleException("requiredCommands must contain strings") }
            ?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("rootfs build manifest has no requiredCommands")
        commands.forEach { command ->
            check(command.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$"))) {
                "invalid required command: $command"
            }
        }

        val runtimeDir = outputDirectory.get().dir("minis-runtime").asFile
        runtimeDir.mkdirs()
        runtimeRootfsArchive.copyTo(runtimeDir.resolve("ubuntu-arm64-rootfs.tar.gz"), overwrite = true)
        val manifest = linkedMapOf<String, Any>(
            "schemaVersion" to 2,
            "runtimeVersion" to appVersionName,
            "minisdVersion" to appVersionName,
            "minisdSha256" to sha256(binaryFile),
            "protocolVersion" to 1,
            "layoutVersion" to 2,
            "abi" to "arm64-v8a",
            "rootfsVersion" to rootfsVersion,
            "rootfsSha256" to rootfsSha,
            "rootfsRelease" to release,
            "rootfsProfile" to profile,
            "rootfsUpstreamSha256" to upstream,
            "provisionRevision" to provisionRevision,
            "requiredCommands" to commands,
        )
        runtimeDir.resolve("runtime-manifest.json").writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n",
        )
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            packageRuntimeAssets,
            PackageRuntimeAssetsTask::outputDirectory,
        )
    }
}

fun verifyPackagedRuntimeApk(apk: java.io.File) {
    if (!apk.isFile) throw GradleException("APK was not produced: $apk")
    ZipFile(apk).use { zip ->
        val lib = zip.getEntry("lib/arm64-v8a/libminisd.so")
            ?: throw GradleException("APK is missing lib/arm64-v8a/libminisd.so")
        val rootfs = zip.getEntry("assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz")
            ?: throw GradleException("APK is missing packaged Ubuntu rootfs")
        val manifestEntry = zip.getEntry("assets/minis-runtime/runtime-manifest.json")
            ?: throw GradleException("APK is missing authoritative runtime manifest")
        val actualMinisd = zip.getInputStream(lib).use { sha256(it) }
        val actualRootfs = zip.getInputStream(rootfs).use { sha256(it) }
        val manifestText = zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
        @Suppress("UNCHECKED_CAST")
        val manifest = JsonSlurper().parseText(manifestText) as Map<*, *>
        check((manifest["schemaVersion"] as? Number)?.toInt() == 2) { "runtime schemaVersion must be 2" }
        check((manifest["protocolVersion"] as? Number)?.toInt() == 1) { "runtime protocolVersion must be 1" }
        check((manifest["layoutVersion"] as? Number)?.toInt() == 2) { "runtime layoutVersion must be 2" }
        check(manifest["abi"] == "arm64-v8a") { "runtime ABI must be arm64-v8a" }
        check(manifest["minisdSha256"] == actualMinisd) { "APK minisd SHA-256 does not match manifest" }
        check(manifest["rootfsSha256"] == actualRootfs) { "APK rootfs SHA-256 does not match manifest" }
        val rootfsVersion = manifest["rootfsVersion"] as? String ?: error("rootfsVersion missing")
        check(rootfsVersion.matches(Regex("^ubuntu-24\\.04-r[1-9][0-9]*-[0-9a-f]{16}$"))) {
            "invalid packaged rootfsVersion: $rootfsVersion"
        }
        check(rootfsVersion.endsWith(actualRootfs.take(16))) { "rootfsVersion is not final-artifact-derived" }
        check((manifest["provisionRevision"] as? Number)?.toInt()?.let { it > 0 } == true) {
            "provisionRevision must be positive"
        }
        val commands = manifest["requiredCommands"] as? List<*>
        check(!commands.isNullOrEmpty()) { "requiredCommands must be packaged" }
        listOf(
            "managed",
            "external_staged",
            "/data/local/tmp/minis-runtime",
            "/data/adb/minis/bin/minisd",
            "/data/adb/minis/run/minisd.sock",
            "/data/adb/minis/run/minisd.pid",
            "/data/adb/minis/policy/policy.json",
        ).forEach { forbidden ->
            check(forbidden !in manifestText) { "obsolete runtime contract returned in APK manifest: $forbidden" }
        }
        val forbiddenAsset = zip.entries().asSequence()
            .map { it.name }
            .firstOrNull { it.startsWith("assets/minisd/") || (it.startsWith("assets/") && it.endsWith(".so")) }
        check(forbiddenAsset == null) { "native executable leaked into assets: $forbiddenAsset" }
    }
}

val verifyDebugMinisdApk by tasks.registering {
    description = "Verifies the debug APK contains the exact APK-owned minisd and rootfs runtime."
    group = "verification"
    dependsOn("packageDebug")
    doLast { verifyPackagedRuntimeApk(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile) }
}

val verifyReleaseMinisdApk by tasks.registering {
    description = "Verifies the release APK contains the exact APK-owned minisd and rootfs runtime."
    group = "verification"
    dependsOn("packageRelease")
    doLast { verifyPackagedRuntimeApk(layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile) }
}

tasks.matching { it.name == "assembleDebug" }
    .configureEach { finalizedBy(verifyDebugMinisdApk) }
tasks.matching { it.name == "assembleRelease" }
    .configureEach { finalizedBy(verifyReleaseMinisdApk) }

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(packageMinisdNative) }
tasks.named("preBuild") { dependsOn(packageRuntimeAssets) }

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

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains:annotations:26.1.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.github.helloooideeeeea:RealTimeCutVADLibraryForAndroid:1.0.5@aar")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.33.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.33.0")

    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.webkit:webkit:1.12.1")

    implementation("sh.calvin.reorderable:reorderable:2.4.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("ch.acra:acra-core:5.12.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20231013")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("junit:junit:4.13.2")
}
