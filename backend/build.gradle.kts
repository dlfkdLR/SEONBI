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

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("net.jqwik:jqwik:1.9.2")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
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
