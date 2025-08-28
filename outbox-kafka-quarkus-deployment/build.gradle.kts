plugins {
    id("java")
}

val quarkusVersion = rootProject.extra["quarkusVersion"]

dependencies {
    implementation(platform("io.quarkus:quarkus-bom:$quarkusVersion"))
    implementation("io.quarkus:quarkus-core-deployment")
    implementation(project(":outbox-kafka-quarkus"))
    implementation("io.quarkus:quarkus-arc-deployment")
    implementation("io.quarkus:quarkus-hibernate-orm-deployment")
    implementation("io.quarkus:quarkus-kafka-client-deployment")
    implementation("io.quarkus:quarkus-jackson-deployment")
    implementation("io.quarkus:quarkus-datasource-deployment")

    // testing
    testImplementation("io.quarkus:quarkus-junit5-internal")
    testImplementation("io.quarkus:quarkus-hibernate-orm")
    testImplementation("io.quarkus:quarkus-jdbc-postgresql")
    testImplementation("io.quarkus:quarkus-flyway")
    testImplementation("io.quarkus:quarkus-kafka-client")
    testImplementation("io.quarkus:quarkus-messaging-kafka")
    testImplementation("io.smallrye.reactive:smallrye-reactive-messaging-in-memory")
    testImplementation("org.awaitility:awaitility")
}
