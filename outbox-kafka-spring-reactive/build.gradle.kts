
// the version is set in parent/root build.gradle.kts

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-parameters"))
}

dependencies {
    val springVersion = "7.0.10"
    val kafkaVersion = "4.2.0"
    val testcontainersVersion = "2.0.5"
    val log4jVersion = "2.25.4"

    implementation("org.springframework:spring-context:$springVersion")

    implementation(platform("org.springframework.data:spring-data-bom:2025.1.8"))
    implementation("org.springframework.data:spring-data-relational")
    implementation("org.springframework.data:spring-data-r2dbc")
    implementation("org.springframework:spring-r2dbc:$springVersion")
    implementation("org.postgresql:r2dbc-postgresql:1.1.3.RELEASE")
    // align netty (pulled in via r2dbc-postgresql / reactor-netty) to a version without known vulnerabilities
    implementation(platform("io.netty:netty-bom:4.2.20.Final"))
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    compileOnly("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    "protobufSupportImplementation"("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("tools.jackson.core:jackson-databind:3.1.8")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("jakarta.annotation:jakarta.annotation-api:3.0.0")
    implementation(project(":commons"))
    implementation(platform("io.micrometer:micrometer-tracing-bom:1.7.2"))
    compileOnly("io.micrometer:micrometer-tracing")

    // testing
    testImplementation(testFixtures(project(":commons")))
    testImplementation("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc:4.1.2") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }
    testImplementation("io.projectreactor:reactor-test:3.8.8")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    testImplementation("org.hamcrest:hamcrest:3.0")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:$testcontainersVersion")
    testImplementation("org.springframework:spring-jdbc:$springVersion")
    testImplementation("io.r2dbc:r2dbc-pool:1.0.2.RELEASE")
    // update gson version to fix a conflict of toxiproxy dependency and spring GsonAutoConfiguration
    testRuntimeOnly("com.google.code.gson:gson:2.14.0")
    testImplementation("org.postgresql:postgresql:42.7.14")
    testRuntimeOnly("io.asyncer:r2dbc-mysql:1.4.3")
    testImplementation("org.flywaydb:flyway-database-postgresql:13.12.2")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:10.0.0")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    testImplementation("org.apache.commons:commons-dbcp2:2.14.0")
    testImplementation("io.micrometer:micrometer-tracing-test")
}
