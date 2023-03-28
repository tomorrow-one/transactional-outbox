
// the version is set in parent/root build.gradle.kts

java.sourceCompatibility = JavaVersion.VERSION_1_8

dependencies {
    implementation("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("org.apache.kafka:kafka-clients:3.3.1")
    implementation("org.springframework:spring-core:5.3.23")
    implementation("org.slf4j:slf4j-api:2.0.7")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.1")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.6")
}
