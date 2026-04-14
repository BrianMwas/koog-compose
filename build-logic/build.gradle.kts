plugins {
    `kotlin-dsl`
    `maven-publish`
}

dependencies {
    compileOnly(gradleApi())

    // Vanniktech Maven Publish plugin — api for types in convention plugin + runtime
    api("com.vanniktech:gradle-maven-publish-plugin:0.34.0")

    // Dokka classes for convention plugin
    api("org.jetbrains.dokka:dokka-gradle-plugin:1.9.20")
}
