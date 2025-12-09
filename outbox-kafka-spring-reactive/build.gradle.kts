
// the version is set in parent/root build.gradle.kts

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-parameters"))
}

dependencies {
    val springVersion = "7.0.1"
    val kafkaVersion = "4.0.0"
    val testcontainersVersion = "1.21.3"
    val log4jVersion = "2.25.1"

    implementation("org.springframework:spring-context:$springVersion")

    implementation(platform("org.springframework.data:spring-data-bom:2025.1.0"))
    implementation("org.springframework.data:spring-data-relational")
    implementation("org.springframework.data:spring-data-r2dbc")
    implementation("org.springframework:spring-r2dbc:$springVersion")
    implementation("org.postgresql:r2dbc-postgresql:1.1.1.RELEASE")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    "protobufSupportImplementation"("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("tools.jackson.core:jackson-databind:3.0.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("jakarta.annotation:jakarta.annotation-api:3.0.0")
    implementation(project(":commons"))
    implementation(platform("io.micrometer:micrometer-tracing-bom:1.6.0"))
    compileOnly("io.micrometer:micrometer-tracing")

    // testing
    testImplementation(testFixtures(project(":commons")))
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc:4.0.0") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }
    testImplementation("io.projectreactor:reactor-test:3.7.11")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    testImplementation("org.hamcrest:hamcrest:3.0")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.springframework:spring-jdbc:$springVersion")
    testImplementation("io.r2dbc:r2dbc-pool:1.0.2.RELEASE")
    // update gson version to fix a conflict of toxiproxy dependency and spring GsonAutoConfiguration
    testRuntimeOnly("com.google.code.gson:gson:2.13.2")
    testImplementation("org.postgresql:postgresql:42.7.8")
    testImplementation("org.flywaydb:flyway-database-postgresql:11.19.0")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:10.0.0")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    testImplementation("org.apache.commons:commons-dbcp2:2.13.0")
    testImplementation("io.micrometer:micrometer-tracing-test")
}
