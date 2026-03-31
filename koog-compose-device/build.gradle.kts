import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ksp)
}

kotlin {
    explicitApi()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    // Add iOS targets for CMP parity
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api() so consumers get core transitively
            api(project(":koog-compose-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            
            // Room KMP dependencies
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":koog-compose-testing"))
        }
        androidMain.dependencies {
            implementation(libs.play.services.location)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.work.runtime.ktx)
        }
        
        val iosMain by creating {
            dependsOn(commonMain.get())
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "io.github.koogcompose.device"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Room compiler needs to be added here for KMP
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
