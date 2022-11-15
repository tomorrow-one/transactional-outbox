
// the version is set in parent/root build.gradle.kts

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

dependencies {

    implementation("org.springframework:spring-context:5.3.3")

    implementation("org.springframework.data:spring-data-relational:2.1.4")
    implementation("org.springframework.data:spring-data-r2dbc:1.2.3")
    implementation("org.springframework:spring-r2dbc:5.3.3")
    implementation("io.r2dbc:r2dbc-postgresql:0.8.7.RELEASE")
    implementation("org.apache.kafka:kafka-clients:2.5.0")
    implementation("com.google.protobuf:protobuf-java:3.12.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.12.2")
    implementation(project(":commons"))
    implementation("org.slf4j:slf4j-api:1.7.30")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // testing
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:2.4.2")
    testImplementation("org.springframework:spring-test:5.3.3")
    testImplementation("io.projectreactor:reactor-test:3.4.3")
    testImplementation(platform("org.junit:junit-bom:5.7.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.hamcrest:hamcrest:2.2")
    testImplementation("org.testcontainers:junit-jupiter:1.17.5")
    testImplementation("org.springframework:spring-jdbc:5.3.3")
    testImplementation("io.r2dbc:r2dbc-pool:0.8.6.RELEASE")
    testImplementation("org.testcontainers:postgresql:1.17.5")
    testImplementation("org.testcontainers:kafka:1.17.5")
    testImplementation("org.testcontainers:toxiproxy:1.17.5")
    // update gson version to fix a conflict of toxiproxy dependency and spring GsonAutoConfiguration
    testRuntimeOnly("com.google.code.gson:gson:2.10")
    testImplementation("org.postgresql:postgresql:42.5.0")
    testImplementation("org.flywaydb:flyway-core:9.7.0")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:7.0.0")
    testImplementation("org.springframework.kafka:spring-kafka:2.5.4.RELEASE")
    testImplementation("org.springframework.kafka:spring-kafka-test:2.5.4.RELEASE")
    testImplementation("org.awaitility:awaitility:4.0.3")
    testImplementation("org.apache.logging.log4j:log4j-core:2.13.3")
    testImplementation("org.apache.logging.log4j:log4j-slf4j-impl:2.13.3")
}
