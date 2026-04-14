pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.vanniktech.maven.publish" -> useModule("com.vanniktech:gradle-maven-publish-plugin:0.34.0")
                "org.jetbrains.dokka" -> useModule("org.jetbrains.dokka:dokka-gradle-plugin:1.9.20")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
