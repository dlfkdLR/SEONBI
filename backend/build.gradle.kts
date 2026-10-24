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
 runtimeOnly("org.postgresql:postgresql")
 testImplementation("org.springframework.boot:spring-boot-starter-test")
 testImplementation("org.testcontainers:junit-jupiter")
 testImplementation("org.testcontainers:postgresql")
 testImplementation("net.jqwik:jqwik:1.9.3")
 testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
 testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:all") }
tasks.withType<Test>().configureEach { useJUnitPlatform() }
spotless { java { googleJavaFormat("1.28.0") } }
dependencyLocking { lockAllConfigurations() }
