import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.*
import org.gradle.api.tasks.testing.logging.TestLogEvent.*

plugins {
    id("java-library")
    id("io.freefair.lombok") version "5.3.0"
    id("com.google.protobuf") version "0.8.14"
    id("maven-publish")
    id("org.sonarqube") version "2.8"
    id("jacoco")
}

group = "one.tomorrow.transactional-outbox"
version = "1.0.3-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(14))
    }
    withSourcesJar()
}

sourceSets {
    test {
        java {
            // declared here so that the IDE knows this src dir
            srcDir("${project.buildDir}/generated/source/proto/test/java")
        }
    }
}

repositories {
    maven(url = "https://nexus.live.aws.tomorrow.one/repository/gf") {
        credentials {
            username = System.getenv("NEXUS_USER") ?: project.property("repoUser").toString()
            password = System.getenv("NEXUS_PASSWORD") ?: project.property("repoPassword").toString()
        }
    }
}

val protobufVersion = "3.12.2"

dependencies {
    val springVersion = "5.3.3"
    val springDataVersion = "2.1.4"
    val kafkaVersion = "2.5.0"
    val springKafkaVersion = "2.5.4.RELEASE"
    val log4jVersion = "2.13.3"

    implementation("org.springframework:spring-context:$springVersion")

    implementation("org.springframework.data:spring-data-relational:$springDataVersion")
    implementation("org.springframework.data:spring-data-r2dbc:1.2.3")
    implementation("org.springframework:spring-r2dbc:$springVersion")
    implementation("io.r2dbc:r2dbc-postgresql:0.8.7.RELEASE")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("one.tomorrow.kafka:kafka-utils:0.4")
    implementation("org.slf4j:slf4j-api:1.7.30")

    // testing
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:2.4.2")
    testImplementation("org.springframework:spring-test:$springVersion")
    testImplementation(platform("org.junit:junit-bom:5.7.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.testcontainers:junit-jupiter:1.15.1")
    testImplementation("org.springframework:spring-jdbc:$springVersion")
    testImplementation("io.r2dbc:r2dbc-pool:0.8.6.RELEASE")
    testImplementation("org.testcontainers:postgresql:1.15.1")
    testImplementation("org.postgresql:postgresql:42.2.9")
    testImplementation("org.flywaydb:flyway-core:5.2.4")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:6.4.0")
    testImplementation("org.springframework.kafka:spring-kafka:$springKafkaVersion")
    testImplementation("org.springframework.kafka:spring-kafka-test:$springKafkaVersion")
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j-impl:$log4jVersion")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
}

sonarqube {
    properties {
        property("sonar.projectName", "transactional-outbox")
        property("sonar.host.url", "https://sonar.live.aws.tomorrow.one")
        property("sonar.login", System.getenv("SONAR_LOGIN_ASPEN"))
        property("sonar.projectKey", "one.tomorrow:transactional-outbox")
        property("sonar.branch.name", System.getenv("CI_COMMIT_REF_NAME"))
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco.xml")
    }
}

tasks.withType<Test> {
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
        xml.apply {
            isEnabled = true
            destination = File("build/reports/jacoco.xml")
        }
        executionData(tasks.withType<Test>())
    }
}

publishing {
    publications {
        create<MavenPublication>("default") {
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
        maven {
            credentials {
                username = System.getenv("NEXUS_USER") ?: project.property("repoUser").toString()
                password = System.getenv("NEXUS_PASSWORD") ?: project.property("repoPassword").toString()
            }
            val releasesRepoUrl = "https://nexus.live.aws.tomorrow.one/repository/maven-releases"
            val snapshotsRepoUrl = "https://nexus.live.aws.tomorrow.one/repository/maven-snapshots"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
        }
    }
}
