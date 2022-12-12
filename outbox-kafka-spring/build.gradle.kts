
// the version is set in parent/root build.gradle.kts

dependencies {
    val springVersion = "6.0.2"
    val hibernateVersion = "5.6.14.Final"
    val kafkaVersion = "3.3.1"
    val springKafkaVersion = "3.0.0"
    val log4jVersion = "2.19.0"

    implementation("org.springframework:spring-context:$springVersion")
    implementation("org.springframework:spring-orm:$springVersion")
    implementation("org.hibernate:hibernate-core:$hibernateVersion")
    implementation("org.hibernate:hibernate-java8:$hibernateVersion")
    implementation("com.vladmihalcea:hibernate-types-52:2.21.1")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation(project(":commons"))
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // testing
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    testImplementation("org.springframework:spring-test:$springVersion")
    testImplementation("org.testcontainers:postgresql:1.17.6")
    testImplementation("org.postgresql:postgresql:42.5.1")
    testImplementation("org.flywaydb:flyway-core:9.15.2")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:7.0.0")
    testImplementation("org.apache.kafka:kafka_2.13:$kafkaVersion") // specify explicitly to prevent conflicts of different server and client versions
    testImplementation("org.springframework.kafka:spring-kafka:$springKafkaVersion")
    testImplementation("org.springframework.kafka:spring-kafka-test:$springKafkaVersion")
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
}

// conflict of vladmihalcea regarding jackson:
//   Caused by: com.fasterxml.jackson.databind.JsonMappingException: Scala module 2.10.2 requires Jackson Databind version >= 2.10.0 and < 2.11.0
// therefore we exclude the jackson-module-scala_2.12 pulled in by kafka to fix this
configurations {
    testImplementation {
        exclude("com.fasterxml.jackson.module", "jackson-module-scala_2.12")
    }
}
