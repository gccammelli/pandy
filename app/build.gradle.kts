plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.gccammelli.pandy"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.gccammelli.pandy"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.5"
    }

    buildTypes {
        release {
            // R8: shrink and optimise code and resources (smaller APK; F-Droid asks for it).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Release signing only when the key is given (GitHub releases, from CI secrets). F-Droid builds unsigned and
    // checks its build matches ours, so both APKs are interchangeable.
    val ks = System.getenv("PANDY_KEYSTORE")
    if (ks != null) {
        signingConfigs.create("release") {
            storeFile = file(ks)
            storePassword = System.getenv("PANDY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PANDY_KEY_ALIAS")
            keyPassword = System.getenv("PANDY_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    dependenciesInfo { includeInApk = false; includeInBundle = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    implementation(libs.kotlinx.coroutines.android)
}
