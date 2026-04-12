import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.dokka)
    id("publish-convention")
}

kotlin {
    explicitApi()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        androidMain.dependencies {
            api(project(":koog-compose-core"))
            implementation(libs.litertlm.android)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

android {
    namespace = "io.github.koogcompose.litertlm"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
