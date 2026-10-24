package com.seonbi.engine;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 엔진은 순수 계산 코드라 프레임워크·DB·AI 계층을 알면 안 된다 (개발기획서 5.11, 13.1). */
@AnalyzeClasses(
    packages = "com.seonbi.engine",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule 엔진은_스프링을_쓰지_않는다 =
      noClasses().should().dependOnClassesThat().resideInAnyPackage("org.springframework..");

  @ArchTest
  static final ArchRule 엔진은_JPA를_쓰지_않는다 =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("jakarta.persistence..", "org.hibernate..");

  @ArchTest
  static final ArchRule 엔진은_다른_계층을_알지_못한다 =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.seonbi.ai..",
              "com.seonbi.domain..",
              "com.seonbi.repository..",
              "com.seonbi.service..",
              "com.seonbi.web..",
              "com.seonbi.dto..",
              "com.seonbi.core..");
}
