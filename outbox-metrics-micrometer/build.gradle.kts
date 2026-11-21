dependencies {
    api(project(":outbox-metrics-api"))
    api("io.micrometer:micrometer-core:1.16.5")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}
