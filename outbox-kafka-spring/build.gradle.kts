import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.*

plugins {
    id("java-library")
    id("io.freefair.lombok") version "5.3.0"
    id("com.google.protobuf") version "0.8.14"
    id("maven-publish")
    //id("io.spring.dependency-management") version "1.0.10.RELEASE"
    id("org.sonarqube") version "2.8"
    id("jacoco")
}

group = "one.tomorrow.transactional-outbox"
version = "1.1.1"

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
    maven(url = "https://nexus.dev.internal.aws.tomorrow.one/repository/gf") {
        credentials {
            username = System.getenv("NEXUS_USER") ?: project.property("repoUser").toString()
            password = System.getenv("NEXUS_PASSWORD") ?: project.property("repoPassword").toString()
        }
    }
}

val protobufVersion = "3.12.2"

dependencies {
    val springVersion = "5.2.8.RELEASE"
    val hibernateVersion = "5.4.18.Final"
    val kafkaVersion = "2.5.0"
    val springKafkaVersion = "2.5.4.RELEASE"
    val log4jVersion = "2.13.3"

    implementation("org.springframework:spring-context:$springVersion")
    implementation("org.springframework:spring-orm:$springVersion")
    implementation("org.hibernate:hibernate-core:$hibernateVersion")
    implementation("org.hibernate:hibernate-java8:$hibernateVersion")
    implementation("com.vladmihalcea:hibernate-types-52:2.10.2")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("one.tomorrow.kafka:kafka-utils:0.8")
    implementation("org.slf4j:slf4j-api:1.7.30")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // testing
    testImplementation("junit:junit:4.13")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.7.0")
    testImplementation("org.springframework:spring-test:$springVersion")
    testImplementation("org.testcontainers:postgresql:1.16.3")
    testImplementation("org.postgresql:postgresql:42.2.9")
    testImplementation("org.flywaydb:flyway-core:5.2.4")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:6.4.0")
    testImplementation("org.springframework.kafka:spring-kafka:$springKafkaVersion")
    testImplementation("org.springframework.kafka:spring-kafka-test:$springKafkaVersion")
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j-impl:$log4jVersion")
}

// conflict of vladmihalcea regarding jackson:
//   Caused by: com.fasterxml.jackson.databind.JsonMappingException: Scala module 2.10.2 requires Jackson Databind version >= 2.10.0 and < 2.11.0
// therefore we exclude the jackson-module-scala_2.12 pulled in by kafka to fix this
configurations {
    testImplementation {
        exclude("com.fasterxml.jackson.module", "jackson-module-scala_2.12")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
}

sonarqube {
    properties {
        property("sonar.projectName", "transactional-outbox")
        property("sonar.host.url", "https://sonar.dev.internal.aws.tomorrow.one")
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
            val releasesRepoUrl = "https://nexus.dev.internal.aws.tomorrow.one/repository/maven-releases"
            val snapshotsRepoUrl = "https://nexus.dev.internal.aws.tomorrow.one/repository/maven-snapshots"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
        }
    }
}
