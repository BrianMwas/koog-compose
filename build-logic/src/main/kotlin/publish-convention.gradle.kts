import org.gradle.kotlin.dsl.*

// ── Coordinates must be set BEFORE applying vanniktech plugin ──────────────────
val GROUP: String by project
val VERSION_NAME: String by project
val POM_ARTIFACT_ID: String by project
val POM_NAME: String by project
val POM_DESCRIPTION: String by project

group = GROUP
version = VERSION_NAME

apply(plugin = "com.vanniktech.maven.publish")
apply(plugin = "org.jetbrains.dokka")

configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
    // Uses Central Portal by default in v0.34.0+
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set(POM_NAME)
        description.set(POM_DESCRIPTION)
        url.set("https://github.com/brianmwas/koog-compose")
        inceptionYear.set("2025")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("brianmwas")
                name.set("Brian")
                url.set("https://github.com/brianmwas")
            }
        }
        scm {
            url.set("https://github.com/brianmwas/koog-compose")
            connection.set("scm:git:git://github.com/brianmwas/koog-compose.git")
            developerConnection.set("scm:git:ssh://git@github.com/brianmwas/koog-compose.git")
        }
        issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/brianmwas/koog-compose/issues")
        }
    }
}
