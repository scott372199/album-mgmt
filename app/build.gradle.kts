plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "top.chenyouyi.album"
version = "0.5.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Web + Actuator / Web 与可观测端点
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Prometheus 指标注册 / Prometheus meter registry
    implementation("io.micrometer:micrometer-registry-prometheus")

    // scrypt 共享口令校验（本地 fixture 实现，不连 SSM）/ scrypt shared-key verification
    // BouncyCastle 提供 RFC 7914 scrypt，零额外云依赖 / zero cloud dependency
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")

    // AWS 只读 adapter 的 SDK client 注入点；本地 profile 绝不构造真实 client、不连真实云
    // AWS read-adapter SDK client injection point; the local profile never builds a real client
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
