import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

fun quoted(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(char)
        }
    }
    append('"')
}

fun sampleConfig(envName: String, gradlePropertyName: String): String =
    providers.environmentVariable(envName)
        .orElse(providers.gradleProperty(gradlePropertyName))
        .orElse("")
        .get()

val sampleProvider = sampleConfig("KOOG_SAMPLE_PROVIDER", "koog.sample.provider")
val sampleApiKey = sampleConfig("KOOG_SAMPLE_API_KEY", "koog.sample.apiKey")
val sampleModel = sampleConfig("KOOG_SAMPLE_MODEL", "koog.sample.model")
val sampleBaseUrl = sampleConfig("KOOG_SAMPLE_BASE_URL", "koog.sample.baseUrl")

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(project(":koog-compose-ui"))
            implementation(project(":koog-compose-device"))
            implementation(project(":koog-compose-testing"))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.material3.v190)
        }
    }
}

android {
    namespace = "io.github.koogcompose.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.koogcompose.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SAMPLE_PROVIDER", quoted(sampleProvider))
        buildConfigField("String", "SAMPLE_API_KEY", quoted(sampleApiKey))
        buildConfigField("String", "SAMPLE_MODEL", quoted(sampleModel))
        buildConfigField("String", "SAMPLE_BASE_URL", quoted(sampleBaseUrl))
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}
