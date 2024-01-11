// the version is set in parent/root build.gradle.kts

dependencies {
    val springVersion = "6.1.2"
    val kafkaVersion = "3.6.1"
    val springKafkaVersion = "3.0.12"
    val log4jVersion = "2.22.1"
    val testcontainersVersion = "1.19.3"

    implementation("org.springframework:spring-context:$springVersion")
    implementation("org.springframework:spring-jdbc:$springVersion")
    implementation("org.postgresql:postgresql:42.7.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    "protobufSupportImplementation"("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("org.slf4j:slf4j-api:2.0.11")
    implementation("jakarta.annotation:jakarta.annotation-api:2.1.1")
    implementation(project(":commons"))

    // testing
    testImplementation(testFixtures(project(":commons")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("org.mockito:mockito-all:1.10.19")

    testImplementation("org.flywaydb:flyway-database-postgresql:10.5.0")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:9.5.0")

    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    testImplementation("org.apache.commons:commons-dbcp2:2.11.0")
}
