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

// Android Control Batteries
include(":koog-compose-android-workmanager")
// include(":koog-compose-android-alarmmanager")        // Future
// include(":koog-compose-android-notifications")       // Future
// include(":koog-compose-android-connectivity")        // Future
// include(":koog-compose-android-sensors")             // Future
// include(":koog-compose-android-intents")             // Future

// iOS Control Batteries
// include(":koog-compose-ios-backgroundtasks")         // Future
// include(":koog-compose-ios-usernotifications")       // Future
// include(":koog-compose-ios-healthkit")               // Future

// Sample app to test the library locally
include(":sample-app")
