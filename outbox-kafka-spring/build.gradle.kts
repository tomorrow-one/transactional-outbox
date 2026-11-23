// the version is set in parent/root build.gradle.kts

dependencies {
    val springVersion = "7.0.10"
    val kafkaVersion = "4.4.0"
    val log4jVersion = "2.26.1"
    val slf4jVersion = "2.0.20"

    implementation("org.springframework:spring-context:$springVersion")
    implementation("org.springframework:spring-jdbc:$springVersion")
    runtimeOnly("org.postgresql:postgresql:42.7.14")
    implementation("tools.jackson.core:jackson-databind:3.1.8")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    compileOnly("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    "protobufSupportImplementation"("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("jakarta.annotation:jakarta.annotation-api:3.0.0")
    implementation(project(":commons"))
    implementation(platform("io.micrometer:micrometer-tracing-bom:1.8.0"))
    compileOnly("io.micrometer:micrometer-tracing")

    // testing
    testImplementation(testFixtures(project(":commons")))
    testImplementation("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.awaitility:awaitility:4.3.0")

    testImplementation("org.flywaydb:flyway-database-postgresql:13.12.2")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:10.0.0")

    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
    testImplementation("org.apache.commons:commons-dbcp2:2.14.0")
    testImplementation("io.micrometer:micrometer-tracing-test")
}
