
// the version is set in parent/root build.gradle.kts

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-parameters"))
}

dependencies {
    val springVersion = "6.2.9"
    val springDataVersion = "3.5.3"
    val kafkaVersion = "3.9.0"
    val testcontainersVersion = "1.21.3"
    val log4jVersion = "2.25.1"

    implementation("org.springframework:spring-context:$springVersion")

    implementation("org.springframework.data:spring-data-relational:$springDataVersion")
    implementation("org.springframework.data:spring-data-r2dbc:$springDataVersion")
    implementation("org.springframework:spring-r2dbc:$springVersion")
    implementation("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    "protobufSupportImplementation"("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation(project(":commons"))
    implementation(platform("io.micrometer:micrometer-tracing-bom:1.5.3"))
    compileOnly("io.micrometer:micrometer-tracing")

    // testing
    testImplementation(testFixtures(project(":commons")))
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:3.5.4")
    testImplementation("io.projectreactor:reactor-test:3.7.9")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    testImplementation("org.hamcrest:hamcrest:3.0")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.springframework:spring-jdbc:$springVersion")
    testImplementation("io.r2dbc:r2dbc-pool:1.0.2.RELEASE")
    // update gson version to fix a conflict of toxiproxy dependency and spring GsonAutoConfiguration
    testRuntimeOnly("com.google.code.gson:gson:2.13.1")
    testImplementation("org.postgresql:postgresql:42.7.7")
    testImplementation("org.flywaydb:flyway-database-postgresql:11.11.1")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:10.0.0")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    testImplementation("org.apache.commons:commons-dbcp2:2.13.0")
    testImplementation("io.micrometer:micrometer-tracing-test")
}
