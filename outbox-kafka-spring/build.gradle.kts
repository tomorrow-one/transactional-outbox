
// the version is set in parent/root build.gradle.kts

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(14))
    }
}

dependencies {
    
    implementation("org.springframework:spring-context:5.2.8.RELEASE")
    implementation("org.springframework:spring-orm:5.2.8.RELEASE")
    implementation("org.hibernate:hibernate-core:5.4.18.Final")
    implementation("org.hibernate:hibernate-java8:5.4.18.Final")
    implementation("com.vladmihalcea:hibernate-types-52:2.10.2")
    implementation("org.apache.kafka:kafka-clients:2.5.0")
    implementation("com.google.protobuf:protobuf-java:3.12.2")
    implementation(project(":commons"))
    implementation("org.slf4j:slf4j-api:1.7.30")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // testing
    testImplementation("junit:junit:4.13")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.7.0")
    testImplementation("org.springframework:spring-test:5.2.8.RELEASE")
    testImplementation("org.testcontainers:postgresql:1.17.5")
    testImplementation("org.postgresql:postgresql:42.5.0")
    testImplementation("org.flywaydb:flyway-core:9.7.0")
    testImplementation("org.flywaydb.flyway-test-extensions:flyway-spring-test:7.0.0")
    testImplementation("org.springframework.kafka:spring-kafka:2.5.4.RELEASE")
    testImplementation("org.springframework.kafka:spring-kafka-test:2.5.4.RELEASE")
    testImplementation("org.apache.logging.log4j:log4j-core:2.13.3")
    testImplementation("org.apache.logging.log4j:log4j-slf4j-impl:2.13.3")
}

// conflict of vladmihalcea regarding jackson:
//   Caused by: com.fasterxml.jackson.databind.JsonMappingException: Scala module 2.10.2 requires Jackson Databind version >= 2.10.0 and < 2.11.0
// therefore we exclude the jackson-module-scala_2.12 pulled in by kafka to fix this
configurations {
    testImplementation {
        exclude("com.fasterxml.jackson.module", "jackson-module-scala_2.12")
    }
}
