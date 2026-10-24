plugins {
    java
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
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.compilerArgs.add("-Xlint:all")
}

tasks.test { useJUnitPlatform() }
