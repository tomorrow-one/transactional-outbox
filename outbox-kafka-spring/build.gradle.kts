// the version is set in parent/root build.gradle.kts

dependencies {
    val springVersion = "6.0.10"
    val hibernateVersion = "6.2.7.Final"
    val kafkaVersion = "3.5.1"
    val springKafkaVersion = "3.0.11"
    val log4jVersion = "2.20.0"
    val testcontainersVersion = "1.18.3"

    implementation("org.springframework:spring-context:$springVersion")
    implementation("org.springframework:spring-orm:$springVersion")
    implementation("org.hibernate.orm:hibernate-core:$hibernateVersion")
    implementation("com.vladmihalcea:hibernate-types-60:2.21.1")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
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
    testImplementation("org.testcontainers:toxiproxy:$testcontainersVersion")
    testImplementation("org.postgresql:postgresql:42.6.0")
    testImplementation("org.flywaydb:flyway-core:9.22.0")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:9.5.0")
    testImplementation("org.apache.kafka:kafka_2.13:$kafkaVersion") // specify explicitly to prevent conflicts of different server and client versions
    testImplementation("org.springframework.kafka:spring-kafka:$springKafkaVersion")
    testImplementation("org.springframework.kafka:spring-kafka-test:$springKafkaVersion")
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    testImplementation("org.apache.commons:commons-dbcp2:2.9.0")
}
