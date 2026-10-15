import io.quarkus.deployment.Capability.*
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.MultipleCandidatesDetails

abstract class PreferUnqualifiedQuarkusVariant : AttributeDisambiguationRule<String> {
    override fun execute(details: MultipleCandidatesDetails<String>) {
        if (details.candidateValues.contains("runtime")) {
            details.closestMatch("runtime")
        }
    }
}

plugins {
    id("java")
    id("io.quarkus.extension")
    id("io.quarkus")
}

quarkusExtension {
    deploymentModule = "outbox-kafka-quarkus-deployment"
    capabilities {
        requires(CDI)
        requires(HIBERNATE_ORM)
        requires(TRANSACTIONS)
        requires(KAFKA)
        requires(JACKSON)
    }
}

listOf("prod", "test").forEach { launchMode ->
    dependencies.attributesSchema.attribute(
        Attribute.of("quarkus.$launchMode.deployment-dependency.outbox-kafka-quarkus", String::class.java)
    ) {
        disambiguationRules.add(PreferUnqualifiedQuarkusVariant::class.java)
    }
}

val quarkusVersion = rootProject.extra["quarkusVersion"]

dependencies {
    // Quarkus BOM
    implementation(platform("io.quarkus:quarkus-bom:$quarkusVersion"))

    // Core dependencies
    implementation("io.quarkus:quarkus-hibernate-orm")
    implementation("io.quarkus:quarkus-kafka-client")

    // Messaging dependency - optional
    compileOnly("io.quarkus:quarkus-messaging-kafka")
    // Health check dependencies - optional
    compileOnly("io.quarkus:quarkus-smallrye-health")
    // Tracing dependencies - optional
    compileOnly("io.quarkus:quarkus-opentelemetry")
    compileOnly("io.opentelemetry:opentelemetry-api")

    // Test dependencies
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-flyway")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.quarkus:quarkus-devservices-postgresql:$quarkusVersion")
    testImplementation("io.quarkus:quarkus-jdbc-postgresql")
    testImplementation("io.quarkus:quarkus-jdbc-mysql")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-redpanda")
    testImplementation("org.testcontainers:testcontainers-toxiproxy")
    testImplementation("org.awaitility:awaitility")
    testImplementation("io.quarkus:quarkus-smallrye-fault-tolerance")
    testImplementation("io.quarkus:quarkus-messaging-kafka")
    testImplementation("io.smallrye.reactive:smallrye-reactive-messaging-in-memory")
    testImplementation("io.quarkus:quarkus-smallrye-health")

    // Test tracing dependencies
    testImplementation("io.quarkus:quarkus-opentelemetry")
    testImplementation("io.opentelemetry:opentelemetry-api")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test> {
    // This module is an extension library; Quarkus app augmentation is exercised in tests instead.
    tasks.named("quarkusAppPartsBuild") {
        enabled = false
    }

    afterEvaluate {
        configurations.named("quarkusDeploymentClasspath") {
            attributes.attribute(
                Attribute.of("quarkus.prod.deployment-dependency.outbox-kafka-quarkus", String::class.java),
                "on"
            )
            attributes.attribute(
                Attribute.of("quarkus.test.deployment-dependency.outbox-kafka-quarkus", String::class.java),
                "off"
            )
        }
    }

    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    useJUnitPlatform()
}
