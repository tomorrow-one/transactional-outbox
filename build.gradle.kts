import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.*
import org.jreleaser.model.Active.*
import java.util.*

project(":commons").version = "3.0.1-SNAPSHOT"
project(":outbox-kafka-spring").version = "4.0.1-SNAPSHOT"
project(":outbox-kafka-spring-reactive").version = "4.0.1-SNAPSHOT"

plugins {
    id("java-library")
    id("java-test-fixtures")
    id("io.freefair.lombok") version "9.0.0"
    id("com.google.protobuf") version "0.9.5"
    id("maven-publish")
    id("org.jreleaser") version "1.21.0"
    id("jacoco")
    id("com.github.hierynomus.license") version "0.16.1"
}

val protobufVersion by extra("3.25.5")

// disable JReleaser on root level
jreleaser {
    enabled = false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "java-test-fixtures")
    apply(plugin = "io.freefair.lombok")
    apply(plugin = "com.google.protobuf")
    apply(plugin = "maven-publish")
    apply(plugin = "org.jreleaser")
    apply(plugin = "jacoco")
    apply(plugin = "com.github.hierynomus.license")

    group = "one.tomorrow.transactional-outbox"

    java {
        sourceCompatibility = JavaVersion.VERSION_17

        withJavadocJar()
        withSourcesJar()

        registerFeature("protobufSupport") {
            usingSourceSet(sourceSets["main"])
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint:deprecation"))
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
                        developer {
                            id.set("mrhnrk")
                            name.set("Henrik Adamski")
                            email.set("henrik.adamski@tomorrow.one")
                        }
                        developer {
                            id.set("danielrehmann")
                            name.set("Daniel Rehmann")
                            email.set("daniel.rehmann@tomorrow.one")
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
            maven {
                url = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
            }
        }
    }

    jreleaser {
        gitRootSearch.set(true)
        signing {
            active = ALWAYS
            armored = true
        }
        deploy {
            maven {
                mavenCentral {
                    create("release-deploy") {
                        active = RELEASE
                        namespace = "one.tomorrow"
                        url = "https://central.sonatype.com/api/v1/publisher"
                        stagingRepository("build/staging-deploy")
                    }
                }
                nexus2 {
                    create("snapshot-deploy") {
                        active = SNAPSHOT
                        snapshotUrl = "https://central.sonatype.com/repository/maven-snapshots/"
                        applyMavenCentralRules = true
                        snapshotSupported = true
                        closeRepository = true
                        releaseRepository = true
                        stagingRepository("build/staging-deploy")
                    }
                }
            }
        }
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
