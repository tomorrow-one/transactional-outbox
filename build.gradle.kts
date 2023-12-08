import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.*
import java.util.*

project(":commons").version = "2.2.2-SNAPSHOT"
project(":outbox-kafka-spring").version = "3.2.1-SNAPSHOT"
project(":outbox-kafka-spring-reactive").version = "3.1.2-SNAPSHOT"

plugins {
    id("java-library")
    id("io.freefair.lombok") version "8.4"
    id("com.google.protobuf") version "0.9.4"
    id("maven-publish")
    id("jacoco")
    id("com.github.hierynomus.license") version "0.16.1"
    id("signing")
}

val protobufVersion by extra("3.21.9")

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.freefair.lombok")
    apply(plugin = "com.google.protobuf")
    apply(plugin = "maven-publish")
    apply(plugin = "jacoco")
    apply(plugin = "com.github.hierynomus.license")
    apply(plugin = "signing")

    group = "one.tomorrow.transactional-outbox"

    java {
        sourceCompatibility = JavaVersion.VERSION_17

        withJavadocJar()
        withSourcesJar()

        registerFeature("protobufSupport") {
            usingSourceSet(sourceSets["main"])
        }
    }

    tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    }

    protobuf {
        protoc {
            artifact = "com.google.protobuf:protoc:$protobufVersion"
        }
    }

    license {
        header = file("../LICENSE-header.txt")
        excludes(
            setOf(
                "one/tomorrow/kafka/messages/DeserializerMessages.java",
                "one/tomorrow/transactionaloutbox/test/Sample.java",
                "one/tomorrow/transactionaloutbox/reactive/test/Sample.java"
            )
        ) // java sources generated from proto messages
        include("**/*.java")
        ext["year"] = Calendar.getInstance().get(Calendar.YEAR)
        skipExistingHeaders = true
    }

    val subproject = this

    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set("$groupId:$artifactId")
                    description.set("${subproject.name} module of transactional-outbox library, check README for details.")
                    url.set("https://github.com/tomorrow-one/transactional-outbox")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("magro")
                            name.set("Martin Grotzke")
                            email.set("martin.grotzke@inoio.de")
                        }
                    }
                    scm {
                        url.set("https://github.com/tomorrow-one/transactional-outbox/")
                        connection.set("scm:git:git://github.com/tomorrow-one/transactional-outbox.git")
                        developerConnection.set("scm:git:ssh://github.com/tomorrow-one/transactional-outbox.git")
                    }
                }

            }
        }
        repositories {
            mavenLocal()
            maven {
                val releasesRepoUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                val snapshotsRepoUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)

                credentials {
                    val ossrhUsername: String? by project
                    val ossrhPassword: String? by project
                    username = ossrhUsername
                    password = ossrhPassword
                }
            }
        }
    }

    // 'signing' has to be defined after/below 'publishing' so that it can reference the publication
    signing {
        val signingKeyId: String? by project
        val signingKey: String? by project
        useInMemoryPgpKeys(signingKeyId, signingKey, "")
        sign(publishing.publications["maven"])
    }

}

allprojects {

    repositories {
        mavenCentral()
    }

    tasks.withType<Test> {
        jvmArgs(listOf("--add-opens=java.base/java.lang=ALL-UNNAMED"))
        maxHeapSize = "4g"

        useJUnitPlatform()

        testLogging {
            events(SKIPPED, PASSED, FAILED)
            showStandardStreams = false // change to true to get log output from tests
            exceptionFormat = FULL
        }

        finalizedBy("jacocoTestReport")
    }

    tasks.withType<JacocoReport> {
        reports {
            xml.required.set(true)
            xml.outputLocation.set(File("build/reports/jacoco.xml"))
            executionData(tasks.withType<Test>())
        }
    }
}
