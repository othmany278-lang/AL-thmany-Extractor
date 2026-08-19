plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whatsapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.whatsapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "e2e-1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
