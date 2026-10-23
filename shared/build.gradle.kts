plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // iOS only where it's used: macOS (CI) or on request (-Ppandy.ios, to type-check the iOS code on Linux).
    val ios = System.getProperty("os.name").startsWith("Mac") || providers.gradleProperty("pandy.ios").isPresent
    if (ios) listOf(iosArm64(), iosX64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    sourceSets {
        all {
            languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.media3.exoplayer)
            implementation(libs.androidx.activity.compose)
            implementation(libs.paho.mqtt)
            implementation(libs.media3.exoplayer.rtsp)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.ui)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }
        if (ios) iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidUnitTest.dependencies {
            implementation("org.robolectric:robolectric:4.16.1")
            implementation("androidx.compose.ui:ui-test-junit4:1.9.3")
            implementation("androidx.compose.ui:ui-test-manifest:1.9.3")
        }
    }
}

android {
    namespace = "io.github.gccammelli.pandy.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    testOptions.unitTests.isIncludeAndroidResources = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources {
    packageOfResClass = "io.github.gccammelli.pandy.res"
}

// Robolectric's Conscrypt test provider reads the peer hostname through JDK reflection.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
}

// Keep the full debug application above the agreed line-coverage floor.
kover {
    reports {
        filters {
            excludes {
                // Compose-generated accessors/collectors; no handwritten code uses this package.
                packages("io.github.gccammelli.pandy.res")
            }
        }
        variant("debug") {
            verify { rule { minBound(90) } }
        }
    }
}
