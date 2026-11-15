plugins {
 java
 id("org.springframework.boot") version "3.5.16"
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
 // DB 스키마는 Flyway SQL 마이그레이션(db/migration)이 관리하고 Hibernate는 검증만 한다. PostgreSQL 지원은 별도 모듈이다
 implementation("org.flywaydb:flyway-core")
 runtimeOnly("org.flywaydb:flyway-database-postgresql")
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

// 종료 규칙·질문 수 보정 실험 (개선사항 3). 예: ./gradlew calibrate -Pargs="--users 150"
tasks.register<JavaExec>("calibrate") {
 group = "verification"
 description = "가상 사용자로 종료 규칙(확신 임계값·정보 이득·질문 상한)을 훑어 보는 보정 실험"
 classpath = sourceSets["test"].runtimeClasspath
 mainClass = "com.seonbi.engine.sim.Calibration"
 args = (project.findProperty("args") as String?)?.split(" ") ?: listOf("--users", "30")
}

// 범용 AI 비교 실험의 입력·선비 결과 JSON (개선사항 4). 예: ./gradlew aiCompareFixture -q
tasks.register<JavaExec>("aiCompareFixture") {
 group = "verification"
 description = "범용 AI 비교 실험용 가상 사용자 답변과 선비 엔진 결과를 JSON으로 출력"
 classpath = sourceSets["test"].runtimeClasspath
 mainClass = "com.seonbi.engine.sim.AiCompareFixture"
}
