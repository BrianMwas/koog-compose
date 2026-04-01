import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.*

plugins {
    `maven-publish`
    signing
}

// ── Coordinates come from each module's gradle.properties or build.gradle.kts ──
val GROUP: String by project
val VERSION_NAME: String by project
val POM_ARTIFACT_ID: String by project
val POM_NAME: String by project
val POM_DESCRIPTION: String by project

group = GROUP
version = VERSION_NAME

// ── Empty Javadoc JAR (required by Central) ───────────────────────────────────
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    // Attach KDoc output here if you add Dokka later:
    // from(tasks.named("dokkaHtml"))
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = POM_ARTIFACT_ID
        artifact(javadocJar)

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

    repositories {
        maven {
            name = "sonatype"
            // Publishes to the new Central Portal staging API
            val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (VERSION_NAME.endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl

            credentials {
                username = providers.gradleProperty("sonatypeUsername")
                    .orElse(providers.environmentVariable("SONATYPE_USERNAME"))
                    .orNull
                password = providers.gradleProperty("sonatypePassword")
                    .orElse(providers.environmentVariable("SONATYPE_PASSWORD"))
                    .orNull
            }
        }
    }
}

// ── GPG Signing ───────────────────────────────────────────────────────────────
signing {
    // In CI, pass key material via env vars instead of a keyring file
    val signingKey = providers.environmentVariable("GPG_SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("GPG_SIGNING_PASSWORD").orNull

    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    } else {
        // Falls back to ~/.gnupg keyring for local dev
        useGpgCmd()
    }

    // Only sign non-SNAPSHOT releases
    setRequired { !VERSION_NAME.endsWith("SNAPSHOT") && gradle.taskGraph.hasTask("publish") }
    sign(publishing.publications)
}
