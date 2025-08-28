import io.quarkus.deployment.Capability.*

plugins {
    id("java")
    id("io.quarkus.extension")
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

val quarkusVersion = rootProject.extra["quarkusVersion"]

dependencies {
    // Quarkus BOM
    implementation(platform("io.quarkus:quarkus-bom:$quarkusVersion"))

    // Core dependencies
    implementation("io.quarkus:quarkus-hibernate-orm")
    implementation("io.quarkus:quarkus-kafka-client")

    // Optional messaging dependency
    compileOnly("io.quarkus:quarkus-messaging-kafka")

    // Tracing dependencies - optional
    compileOnly("io.quarkus:quarkus-opentelemetry")
    compileOnly("io.opentelemetry:opentelemetry-api")

    // Test dependencies
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-flyway")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.quarkus:quarkus-devservices-postgresql:$quarkusVersion")
    testImplementation("io.quarkus:quarkus-jdbc-postgresql")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:redpanda")
    testImplementation("org.testcontainers:toxiproxy")
    testImplementation("org.awaitility:awaitility")
    testImplementation("io.quarkus:quarkus-smallrye-fault-tolerance")
    testImplementation("io.quarkus:quarkus-messaging-kafka")
    testImplementation("io.smallrye.reactive:smallrye-reactive-messaging-in-memory")

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

    // apply the quarkus plugin so that we get quarkus test support (dev services etc.)
    apply(plugin = "io.quarkus")
    // don't build this module as quarkus app (this leads to failures with jpa), therefore disable this task
    tasks.named("quarkusAppPartsBuild") {
        enabled = false
    }

    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    useJUnitPlatform()
}
