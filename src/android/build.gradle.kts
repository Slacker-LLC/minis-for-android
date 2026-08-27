import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException

plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
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
    }
}
