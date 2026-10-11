import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key lives outside the repo. Point MYVR_KEYSTORE_PROPERTIES at a properties file with
// storeFile / storePassword / keyAlias / keyPassword, or use ~/.android-release/keystore.properties.
val releaseKeystore = Properties().apply {
    val path = System.getenv("MYVR_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.android-release/keystore.properties"
    file(path).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.github.abhiguptag22.myvrplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.abhiguptag22.myvrplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 100
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (!releaseKeystore.isEmpty) {
            create("release") {
                storeFile = file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += listOf("x86_64") } // emulator
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("org.videolan.android:libvlc-all:3.6.5")
}
