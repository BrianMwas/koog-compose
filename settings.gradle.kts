rootProject.name = "koog-compose"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Composite build for convention plugins
includeBuild("build-logic")

// Library modules
include(":koog-compose-core")
include(":koog-compose-ui")
include(":koog-compose-device")
include(":koog-compose-testing")
include(":koog-compose-session-room")

// Background task batteries (platform-specific implementations live in koog-compose-device)
// Android: WorkManager (via androidx.work in koog-compose-device:androidMain)
// iOS: BackgroundTasks (planned)
// Desktop: ScheduledExecutorService (planned)

// Sample app to test the library locally
include(":sample-app")
