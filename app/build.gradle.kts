plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.roboguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.roboguard"
        minSdk = 26
        targetSdk = 28

        versionCode = 1
        versionName = "1.0"

        multiDexEnabled = true
    }

    compileOptions {
        // Android 9 requires Java 8 bytecode
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        // Match Android’s JVM
        jvmTarget = "1.8"
    }

    // IMPORTANT: Netty cannot run on JVM 17 bytecode
    kotlin {
        jvmToolchain(8)
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Ktor Server + Netty
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation("io.ktor:ktor-server-cio:2.3.7")


    // JSON
    implementation(libs.kotlinx.serialization.json)

    // security
    implementation(libs.bundles.bouncycastle)

// QR code
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

// SQLCipher
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    ksp("androidx.room:room-compiler:2.6.1")

// AndroidX SQLite
    implementation("androidx.sqlite:sqlite:2.3.1")

    // Coroutine Unterstützung (optional, falls du suspend functions in DAO nutzt)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("com.google.guava:guava:31.1-android"){
        exclude(group = "com.google.guava", module = "listenablefuture")
    }

}
configurations.all {
    exclude(group = "com.google.guava", module = "listenablefuture")
}


