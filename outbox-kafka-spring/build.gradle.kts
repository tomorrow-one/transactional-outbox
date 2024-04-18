// the version is set in parent/root build.gradle.kts

dependencies {
    val springVersion = "6.1.6"
    val kafkaVersion = "3.7.0"
    val log4jVersion = "2.23.1"

    implementation("org.springframework:spring-context:$springVersion")
    implementation("org.springframework:spring-jdbc:$springVersion")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    "protobufSupportImplementation"("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("jakarta.annotation:jakarta.annotation-api:3.0.0")
    implementation(project(":commons"))

    // testing
    testImplementation(testFixtures(project(":commons")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.awaitility:awaitility:4.2.1")

    testImplementation("org.flywaydb:flyway-database-postgresql:10.11.1")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:10.0.0")

    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    testImplementation("org.apache.commons:commons-dbcp2:2.12.0")
}
