
// the version is set in parent/root build.gradle.kts

dependencies {
    val springVersion = "6.1.12"
    val kafkaVersion = "3.8.0"
    val springKafkaVersion = "3.2.3"
    val sl4jVersion = "2.0.16"
    val junitVersion = "5.11.1"
    val testcontainersVersion = "1.20.1"

    "protobufSupportImplementation"("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("org.springframework:spring-core:$springVersion")
    implementation("org.springframework:spring-context:$springVersion")
    implementation("org.slf4j:slf4j-api:$sl4jVersion")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testRuntimeOnly("org.slf4j:slf4j-simple:$sl4jVersion")

    testFixturesImplementation("org.springframework:spring-test:$springVersion")
    testFixturesImplementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    testFixturesImplementation("org.springframework.kafka:spring-kafka:$springKafkaVersion")
    testFixturesImplementation("org.springframework.kafka:spring-kafka-test:$springKafkaVersion")
    testFixturesImplementation("org.slf4j:slf4j-api:$sl4jVersion")
    testFixturesImplementation("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    testFixturesApi("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testFixturesApi("org.testcontainers:postgresql:$testcontainersVersion")
    testFixturesApi("org.testcontainers:kafka:$testcontainersVersion")
    testFixturesApi("org.testcontainers:toxiproxy:$testcontainersVersion")

}
