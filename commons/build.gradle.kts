
// the version is set in parent/root build.gradle.kts

dependencies {
    implementation("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("org.apache.kafka:kafka-clients:3.5.1")
    implementation("org.springframework:spring-core:6.0.11")
    implementation("org.slf4j:slf4j-api:2.0.9")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.7")
}
