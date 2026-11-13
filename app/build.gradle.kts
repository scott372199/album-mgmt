plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "top.chenyouyi.album"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation(platform("software.amazon.awssdk:bom:2.31.63"))
    implementation("software.amazon.awssdk:cloudfront")
    implementation("software.amazon.awssdk:ecr")
    implementation("software.amazon.awssdk:ecs")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
