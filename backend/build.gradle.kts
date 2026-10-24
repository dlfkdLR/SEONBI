plugins {
    java
    id("com.diffplug.spotless") version "7.0.2"
}

group = "com.seonbi"
version = "0.5.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.apache.commons:commons-math3:3.6.1")
    // AI 계층: JSON 파싱과 출력 스키마 생성 (SDK나 Spring AI는 쓰지 않는다 — 개발기획서 6.4)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("com.github.victools:jsonschema-generator:4.37.0")
    implementation("com.github.victools:jsonschema-module-jackson:4.37.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("net.jqwik:jqwik:1.9.2")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.compilerArgs.add("-Xlint:all")
    // 경고도 오류로 본다 (기획서 13.2: 엔진 패키지 기준). Spring 코드에서 걸리면 B가 이 줄을 조정한다
    options.compilerArgs.add("-Werror")
}

spotless {
    java {
        googleJavaFormat("1.25.2")
        target("src/**/*.java")
    }
}

tasks.test { useJUnitPlatform() }

// 가상 사용자 시뮬레이션 (개발기획서 14장). 예: ./gradlew simulate -Pargs="--scenario both --users 200 --seed 42"
tasks.register<JavaExec>("simulate") {
    group = "verification"
    description = "EIG 질문 선택 vs 랜덤 질문 선택 시뮬레이션"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.seonbi.engine.sim.Simulation"
    args = (project.findProperty("args") as String?)?.split(" ") ?: listOf("--quick")
}
