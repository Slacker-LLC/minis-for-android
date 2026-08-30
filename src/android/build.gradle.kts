import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException

plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}

private const val runtimeGzipAsset = "minis-runtime/ubuntu-arm64-rootfs.tar.gz"
private const val runtimeGzipMergeName = "$runtimeGzipAsset.aapt-preserve"

fun org.gradle.api.Task.requireOutputFile(relativePath: String): java.io.File {
    val matches = outputs.files.files.asSequence()
        .filter { it.isDirectory }
        .map { it.resolve(relativePath) }
        .filter { it.isFile }
        .toList()
    if (matches.size != 1) {
        throw GradleException(
            "$path expected exactly one output '$relativePath', found ${matches.map { it.absolutePath }}",
        )
    }
    return matches.single()
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        val keystorePath = System.getenv("RELEASE_KEYSTORE")?.takeIf { it.isNotBlank() }
        val storePassword = System.getenv("RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
        val keyAlias = System.getenv("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
        val keyPassword = System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        val signingConfigured = listOf(
            keystorePath,
            storePassword,
            keyAlias,
            keyPassword,
        ).all { it != null }

        // AGP 8.10 locks the application DSL before afterEvaluate. finalizeDsl
        // is the supported last mutation point: module defaults have been read,
        // but signing/lint/build-type objects are still mutable.
        val androidComponents = extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        androidComponents.finalizeDsl { android ->
            val release = android.buildTypes.getByName("release")

            if (signingConfigured) {
                val signing = android.signingConfigs.findByName("productionRelease")
                    ?: android.signingConfigs.create("productionRelease")
                signing.storeFile = file(keystorePath!!)
                signing.storePassword = storePassword
                signing.keyAlias = keyAlias
                signing.keyPassword = keyPassword
                release.signingConfig = signing
            } else {
                // Never inherit the app module's historical debug-key fallback.
                release.signingConfig = null
            }

            // The app module historically disabled release lint. Restore it at
            // the final supported DSL hook so lintRelease is a real gate.
            android.lint.checkReleaseBuilds = true
        }

        val requireReleaseSigning = tasks.register("requireReleaseSigning") {
            group = "verification"
            description = "Fails production release packaging unless explicit signing credentials are configured."
            doLast {
                if (!signingConfigured) {
                    throw GradleException(
                        "Production release signing is not configured. Set RELEASE_KEYSTORE, " +
                            "RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD. " +
                            "Debug signing is intentionally forbidden for release artifacts.",
                    )
                }
                val store = file(keystorePath!!)
                if (!store.isFile) {
                    throw GradleException("RELEASE_KEYSTORE does not exist or is not a file: $store")
                }
            }
        }

        tasks.matching {
            it.name == "assembleRelease" ||
                it.name == "bundleRelease" ||
                it.name == "packageRelease"
        }.configureEach {
            dependsOn(requireReleaseSigning)
        }

        // AAPT treats a terminal .gz asset as a pre-compressed input: it gunzips
        // the payload and removes the suffix before the merged-assets artifact is
        // handed to packaging. Keep the authoritative gzip bytes under an
        // internal non-.gz suffix while AAPT merges assets, then restore the exact
        // APK-owned runtime name before compress/package tasks consume the merge
        // output. These are build-directory-only names; no external staging or
        // source-tree mutation is involved.
        afterEvaluate {
            tasks.matching {
                it.name == "packageDebugRuntimeAssets" || it.name == "packageReleaseRuntimeAssets"
            }.configureEach {
                doLast {
                    val source = requireOutputFile(runtimeGzipAsset)
                    val protected = source.parentFile.resolve(source.name + ".aapt-preserve")
                    if (protected.exists()) {
                        throw GradleException("AAPT-preserved runtime asset already exists: $protected")
                    }
                    java.nio.file.Files.move(source.toPath(), protected.toPath())
                }
            }

            tasks.matching {
                it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets"
            }.configureEach {
                doLast {
                    val protected = requireOutputFile(runtimeGzipMergeName)
                    val restored = protected.parentFile.resolve("ubuntu-arm64-rootfs.tar.gz")
                    if (restored.exists()) {
                        throw GradleException("Restored runtime gzip asset already exists: $restored")
                    }
                    java.nio.file.Files.move(protected.toPath(), restored.toPath())
                }
            }
        }
    }
}
