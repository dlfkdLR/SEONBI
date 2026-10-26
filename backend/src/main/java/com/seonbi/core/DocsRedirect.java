package com.seonbi.core;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** /api-docs → 한국어 API 문서 화면(static/api-docs/index.html). */
@Configuration
public class DocsRedirect implements WebMvcConfigurer {
  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addRedirectViewController("/api-docs", "/api-docs/index.html");
  }
}
