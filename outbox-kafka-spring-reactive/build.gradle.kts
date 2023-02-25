
// the version is set in parent/root build.gradle.kts

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

dependencies {
    val springVersion = "5.3.23"
    val springDataVersion = "2.4.5"
    val kafkaVersion = "3.3.1"
    val springKafkaVersion = "2.9.2"
    val testcontainersVersion = "1.17.6"
    val log4jVersion = "2.19.0"

    implementation("org.springframework:spring-context:$springVersion")

    implementation("org.springframework.data:spring-data-relational:$springDataVersion")
    implementation("org.springframework.data:spring-data-r2dbc:1.5.5")
    implementation("org.springframework:spring-r2dbc:$springVersion")
    implementation("io.r2dbc:r2dbc-postgresql:0.8.13.RELEASE")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.14.1")
    implementation(project(":commons"))
    implementation("org.slf4j:slf4j-api:2.0.5")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // testing
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:2.7.5")
    testImplementation("org.springframework:spring-test:$springVersion")
    testImplementation("io.projectreactor:reactor-test:3.5.0")
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.hamcrest:hamcrest:2.2")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.springframework:spring-jdbc:$springVersion")
    testImplementation("io.r2dbc:r2dbc-pool:1.0.0.RELEASE")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:kafka:$testcontainersVersion")
    testImplementation("org.testcontainers:toxiproxy:$testcontainersVersion")
    // update gson version to fix a conflict of toxiproxy dependency and spring GsonAutoConfiguration
    testRuntimeOnly("com.google.code.gson:gson:2.10")
    testImplementation("org.postgresql:postgresql:42.5.1")
    testImplementation("org.flywaydb:flyway-core:9.15.1")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:7.0.0")
    testImplementation("org.springframework.kafka:spring-kafka:$springKafkaVersion")
    testImplementation("org.springframework.kafka:spring-kafka-test:$springKafkaVersion")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
}
