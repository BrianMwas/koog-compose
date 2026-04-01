plugins {
    `kotlin-dsl`
    `maven-publish`
}

dependencies {
    // Gives the convention plugin access to the Gradle Kotlin DSL types
    compileOnly(gradleApi())
    
    // Dokka classes for convention plugin
    compileOnly("org.jetbrains.dokka:dokka-gradle-plugin:1.9.20")
}
