plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

    // Netty server engine
    implementation(libs.ktor.server.netty)

    // JSON
    implementation(libs.kotlinx.serialization.json)
}


