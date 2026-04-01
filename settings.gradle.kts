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

// Library modules
include(":koog-compose-core")
include(":koog-compose-ui")
include(":koog-compose-device")
include(":koog-compose-testing")
include(":koog-compose-session-room")

// Android Control Batteries
include(":koog-compose-android-workmanager")
include(":koog-compose-android-alarmmanager")
include(":koog-compose-android-notifications")
include(":koog-compose-android-connectivity")
include(":koog-compose-android-sensors")
include(":koog-compose-android-intents")

// iOS Control Batteries
include(":koog-compose-ios-backgroundtasks")
include(":koog-compose-ios-usernotifications")
include(":koog-compose-ios-healthkit")

// Sample app to test the library locally
include(":sample-app")
