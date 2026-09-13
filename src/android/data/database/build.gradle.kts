plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.openminis.data.database"
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
}

ksp {
    // Keep Room schema history in the existing app/schemas location; its path is
    // part of downgrade/migration verification and must not move with the code.
    arg("room.schemaLocation", "${rootProject.projectDir}/app/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":platform:logging"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    ksp("androidx.room:room-compiler:2.6.1")
}
