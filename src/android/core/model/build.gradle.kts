plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Android provides org.json at runtime. The JVM core module only needs the
    // API for compilation; unit tests use the standalone implementation.
    compileOnly("org.json:json:20231013")
    testImplementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
}
