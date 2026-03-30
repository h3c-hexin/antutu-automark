plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autorun.antutu"
    compileSdk = 35

    signingConfigs {
        create("platform") {
            storeFile = file("../keystore/platform.jks")
            storePassword = "h3cmagichub8888"
            keyAlias = "h3cmagichub"
            keyPassword = "h3cmagichub8888"
        }
    }

    defaultConfig {
        applicationId = "com.autorun.antutu"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("platform")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("platform")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
