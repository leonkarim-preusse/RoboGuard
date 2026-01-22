plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.roboguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.roboguard"
        minSdk = 26
        targetSdk = 28

        versionCode = 1
        versionName = "1.0"

        multiDexEnabled = true
    }

    compileOptions {
        // Ermöglicht moderne Java-Features auf alten Android-Versionen
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        // Match Android’s JVM
        jvmTarget = "1.8"
    }


    kotlin {
        jvmToolchain(17)
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
    // Compose & AndroidX (bleiben gleich)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Ktor Server 2.3.12 - Hier die harten Versionen statt 3.3.3
    val ktorVersion = "2.3.12"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-host-common:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-network-tls:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:2.3.12")

    implementation("io.ktor:ktor-network-tls-certificates:${ktorVersion}")

    // JSON (Achte darauf, dass dies zu Ktor 2 passt, meist 1.6.3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Security & Rest (bleiben gleich)
    implementation(libs.bundles.bouncycastle)
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Room & Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite:2.3.1")

    // Coroutines & Logging
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.tony19:logback-android:3.0.0")
    implementation("org.slf4j:slf4j-api:2.0.7")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("com.google.guava:guava:31.1-android") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
}
configurations.all {
    exclude(group = "com.google.guava", module = "listenablefuture")
}


