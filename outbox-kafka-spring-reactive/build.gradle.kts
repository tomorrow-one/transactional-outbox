
// the version is set in parent/root build.gradle.kts

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-parameters"))
}

dependencies {
    val springVersion = "6.1.4"
    val springDataVersion = "3.2.3"
    val kafkaVersion = "3.7.0"
    val testcontainersVersion = "1.19.6"
    val log4jVersion = "2.23.0"

    implementation("org.springframework:spring-context:$springVersion")

    implementation("org.springframework.data:spring-data-relational:$springDataVersion")
    implementation("org.springframework.data:spring-data-r2dbc:3.2.3")
    implementation("org.springframework:spring-r2dbc:$springVersion")
    implementation("org.postgresql:r2dbc-postgresql:1.0.4.RELEASE")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    "protobufSupportImplementation"("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation(project(":commons"))

    // testing
    testImplementation(testFixtures(project(":commons")))
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:3.2.3")
    testImplementation("io.projectreactor:reactor-test:3.6.2")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    testImplementation("org.hamcrest:hamcrest:2.2")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.springframework:spring-jdbc:$springVersion")
    testImplementation("io.r2dbc:r2dbc-pool:1.0.1.RELEASE")
    // update gson version to fix a conflict of toxiproxy dependency and spring GsonAutoConfiguration
    testRuntimeOnly("com.google.code.gson:gson:2.10.1")
    testImplementation("org.postgresql:postgresql:42.7.1")
    testImplementation("org.flywaydb:flyway-database-postgresql:10.9.0")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:10.0.0")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    testImplementation("org.apache.commons:commons-dbcp2:2.11.0")
}
