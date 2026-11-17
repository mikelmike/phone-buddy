import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.phonebuddy.app"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.phonebuddy.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 90
        versionName = "0.9.0-4b"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "version"
    productFlavors {
        create("full") {
            dimension = "version"
            applicationId = "com.phonebuddy.app"
            versionCode = 90
            versionName = "0.9.0-4b"
            manifestPlaceholders["appLabel"] = "Phone Buddy"
        }
        create("light") {
            dimension = "version"
            applicationId = "com.phonebuddy.light"
            versionCode = 90
            versionName = "0.9.0-4b-light"
            manifestPlaceholders["appLabel"] = "Phone Buddy Light"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file(ksProps.getProperty("storeFile") ?: "keystore/phone-buddy.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                keyAlias = ksProps.getProperty("keyAlias") ?: "phonebuddy"
                storePassword = ksProps.getProperty("storePassword") ?: "phonebuddy123"
                keyPassword = ksProps.getProperty("keyPassword") ?: "phonebuddy123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val relSign = signingConfigs.getByName("release")
            if (relSign.storeFile?.exists() == true) {
                signingConfig = relSign
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity:1.9.3")
}
