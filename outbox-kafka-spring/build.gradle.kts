// the version is set in parent/root build.gradle.kts

dependencies {
    val springVersion = "6.0.8"
    val hibernateVersion = "6.2.1.Final"
    val kafkaVersion = "3.4.0"
    val springKafkaVersion = "3.0.5"
    val log4jVersion = "2.20.0"

    implementation("org.springframework:spring-context:$springVersion")
    implementation("org.springframework:spring-orm:$springVersion")
    implementation("org.hibernate.orm:hibernate-core:$hibernateVersion")
    implementation("com.vladmihalcea:hibernate-types-60:2.21.0")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation(project(":commons"))
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("jakarta.annotation:jakarta.annotation-api:2.1.1")

    // testing
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    testImplementation("org.springframework:spring-test:$springVersion")
    testImplementation("org.testcontainers:postgresql:1.17.6")
    testImplementation("org.postgresql:postgresql:42.6.0")
    testImplementation("org.flywaydb:flyway-core:9.15.2")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:7.0.0")
    testImplementation("org.apache.kafka:kafka_2.13:$kafkaVersion") // specify explicitly to prevent conflicts of different server and client versions
    testImplementation("org.springframework.kafka:spring-kafka:$springKafkaVersion")
    testImplementation("org.springframework.kafka:spring-kafka-test:$springKafkaVersion")
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
}
