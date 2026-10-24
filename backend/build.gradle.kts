plugins {
 java
 id("org.springframework.boot") version "3.5.7"
 id("io.spring.dependency-management") version "1.1.7"
 id("com.diffplug.spotless") version "7.2.1"
}
group = "com.seonbi"
version = "0.1.0"
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories { mavenCentral() }
dependencies {
 implementation("org.springframework.boot:spring-boot-starter-web")
 implementation("org.springframework.boot:spring-boot-starter-data-jpa")
 implementation("org.springframework.boot:spring-boot-starter-validation")
 implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.14")
 implementation("com.github.f4b6a3:uuid-creator:6.1.1")
 // A: 분석 엔진(Well19937c·Gamma)과 AI 출력 JSON 스키마 생성 (개발기획서 5.11, 6.4). Jackson·SLF4J는 Spring Boot가 관리한다
 implementation("org.apache.commons:commons-math3:3.6.1")
 implementation("com.github.victools:jsonschema-generator:4.37.0")
 implementation("com.github.victools:jsonschema-module-jackson:4.37.0")
 runtimeOnly("org.postgresql:postgresql")
 testImplementation("org.springframework.boot:spring-boot-starter-test")
 testImplementation("org.testcontainers:junit-jupiter")
 testImplementation("org.testcontainers:postgresql")
 testImplementation("net.jqwik:jqwik:1.9.3")
 testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
 testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<JavaCompile>().configureEach {
 options.compilerArgs.add("-Xlint:all")
 // 경고도 오류로 본다 (기획서 13.2). 다른 담당 코드에서 걸리면 이 줄을 조정한다
 options.compilerArgs.add("-Werror")
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
spotless { java { googleJavaFormat("1.28.0") } }
dependencyLocking { lockAllConfigurations() }

// 가상 사용자 시뮬레이션 (개발기획서 14장). 예: ./gradlew simulate -Pargs="--scenario both --users 200 --seed 42"
tasks.register<JavaExec>("simulate") {
 group = "verification"
 description = "EIG 질문 선택 vs 랜덤 질문 선택 시뮬레이션"
 classpath = sourceSets["test"].runtimeClasspath
 mainClass = "com.seonbi.engine.sim.Simulation"
 args = (project.findProperty("args") as String?)?.split(" ") ?: listOf("--quick")
}
