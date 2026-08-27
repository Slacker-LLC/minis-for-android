import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException

plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}

// Release hardening is applied after the app module has evaluated so it is
// authoritative even if a downstream/public mirror still contains an older
// release buildType default. Production packaging may never fall back to the
// Android debug signing config.
subprojects {
    pluginManager.withPlugin("com.android.application") {
        afterEvaluate {
            val android = extensions.getByType(ApplicationExtension::class.java)
            val release = android.buildTypes.getByName("release")

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

            if (signingConfigured) {
                val signing = android.signingConfigs.findByName("productionRelease")
                    ?: android.signingConfigs.create("productionRelease")
                signing.storeFile = file(keystorePath!!)
                signing.storePassword = storePassword
                signing.keyAlias = keyAlias
                signing.keyPassword = keyPassword
                release.signingConfig = signing
            } else {
                // Explicitly clear any module-level debug signing fallback.
                // Release compile/lint remains available to public CI, while
                // packaging tasks below fail with a clear credential error.
                release.signingConfig = null
            }

            // AGP 8.10 supports API 36 with the repository's Gradle 8.11.1.
            // Keep only the known broken detector disabled in the app module;
            // release lint itself must run.
            android.lint.checkReleaseBuilds = true

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
        }
    }
}
