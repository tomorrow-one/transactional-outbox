// the version is set in parent/root build.gradle.kts

dependencies {
    val springVersion = "6.1.13"
    val kafkaVersion = "3.8.0"
    val log4jVersion = "2.24.0"

    implementation("org.springframework:spring-context:$springVersion")
    implementation("org.springframework:spring-jdbc:$springVersion")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    "protobufSupportImplementation"("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("jakarta.annotation:jakarta.annotation-api:3.0.0")
    implementation(project(":commons"))

    // testing
    testImplementation(testFixtures(project(":commons")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.awaitility:awaitility:4.2.2")

    testImplementation("org.flywaydb:flyway-database-postgresql:10.17.1")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:10.0.0")

    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    testImplementation("org.apache.commons:commons-dbcp2:2.12.0")
}
