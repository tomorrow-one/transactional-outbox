// the version is set in parent/root build.gradle.kts

dependencies {
    val springVersion = "6.0.13"
    val kafkaVersion = "3.5.1"
    val springKafkaVersion = "3.0.12"
    val log4jVersion = "2.22.0"
    val testcontainersVersion = "1.19.3"

    implementation("org.springframework:spring-context:$springVersion")
    implementation("org.springframework:spring-jdbc:$springVersion")
    implementation("org.postgresql:postgresql:42.7.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    "protobufSupportImplementation"("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation(project(":commons"))
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("jakarta.annotation:jakarta.annotation-api:2.1.1")

    // testing
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("org.mockito:mockito-all:1.10.19")

    testImplementation("org.springframework:spring-test:$springVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:kafka:$testcontainersVersion")
    testImplementation("org.testcontainers:toxiproxy:$testcontainersVersion")
    testImplementation("org.flywaydb:flyway-database-postgresql:10.2.0")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:9.5.0")
    testImplementation("org.apache.kafka:kafka_2.13:$kafkaVersion") // specify explicitly to prevent conflicts of different server and client versions
    testImplementation("org.springframework.kafka:spring-kafka:$springKafkaVersion")
    testImplementation("org.springframework.kafka:spring-kafka-test:$springKafkaVersion") {
        exclude("org.apache.kafka:kafka_2.13", "junit")
    }
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    testImplementation("org.apache.commons:commons-dbcp2:2.11.0")
}
