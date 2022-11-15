
// the version is set in parent/root build.gradle.kts

java.sourceCompatibility = JavaVersion.VERSION_1_8

sourceSets {
    main {
        java {
            // declared here so that the IDE knows this src dir
            srcDir("${project.buildDir}/generated/source/proto/main/java")
        }
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:${rootProject.extra["protobufVersion"]}")
    implementation("org.apache.kafka:kafka-clients:2.5.0")
    implementation("org.springframework:spring-core:5.2.6.RELEASE")
    implementation("org.slf4j:slf4j-api:1.7.30")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.7.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.1")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.3")
}
