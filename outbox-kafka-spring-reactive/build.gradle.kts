
// the version is set in parent/root build.gradle.kts

dependencies {
    val springVersion = "6.0.10"
    val springDataVersion = "3.1.2"
    val kafkaVersion = "3.5.1"
    val springKafkaVersion = "3.0.11"
    val testcontainersVersion = "1.18.3"
    val log4jVersion = "2.20.0"

    implementation("org.springframework:spring-context:$springVersion")

    implementation("org.springframework.data:spring-data-relational:$springDataVersion")
    implementation("org.springframework.data:spring-data-r2dbc:3.1.1")
    implementation("org.springframework:spring-r2dbc:$springVersion")
    implementation("org.postgresql:r2dbc-postgresql:1.0.2.RELEASE")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation(project(":commons"))
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // testing
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:3.1.2")
    testImplementation("org.springframework:spring-test:$springVersion")
    testImplementation("io.projectreactor:reactor-test:3.5.9")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    testImplementation("org.hamcrest:hamcrest:2.2")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.springframework:spring-jdbc:$springVersion")
    testImplementation("io.r2dbc:r2dbc-pool:1.0.1.RELEASE")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:kafka:$testcontainersVersion")
    testImplementation("org.testcontainers:toxiproxy:$testcontainersVersion")
    // update gson version to fix a conflict of toxiproxy dependency and spring GsonAutoConfiguration
    testRuntimeOnly("com.google.code.gson:gson:2.10.1")
    testImplementation("org.postgresql:postgresql:42.6.0")
    testImplementation("org.flywaydb:flyway-core:9.22.0")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:9.5.0")
    testImplementation("org.springframework.kafka:spring-kafka:$springKafkaVersion")
    testImplementation("org.springframework.kafka:spring-kafka-test:$springKafkaVersion")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    testImplementation("org.apache.commons:commons-dbcp2:2.9.0")
}
