plugins {
    `kotlin-dsl`
}

dependencies {
    // Gives the convention plugin access to the Gradle Kotlin DSL types
    compileOnly(gradleApi())
}
