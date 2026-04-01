import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar

apply(plugin = "maven-publish")
apply(plugin = "signing")

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        withType<MavenPublication> {
            artifact(javadocJar)

            pom {
                name.set("koog-compose")
                description.set("Android-first AI orchestration framework for Kotlin Multiplatform.")
                url.set("https://github.com/koogcompose/koog-compose")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("koogcompose")
                        name.set("Brian Mwangi")
                        email.set("brian@koog.ai")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/koogcompose/koog-compose.git")
                    developerConnection.set("scm:git:ssh://github.com/koogcompose/koog-compose.git")
                    url.set("https://github.com/koogcompose/koog-compose")
                }
            }
        }
    }

    repositories {
        maven {
            name = "OSSRH"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = project.findProperty("ossrhUsername")?.toString() ?: ""
                password = project.findProperty("ossrhPassword")?.toString() ?: ""
            }
        }
    }
}

signing {
    val signingKey = project.findProperty("signingKey")?.toString() ?: ""
    val signingPassword = project.findProperty("signingPassword")?.toString() ?: ""
    if (signingKey.isNotEmpty()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
