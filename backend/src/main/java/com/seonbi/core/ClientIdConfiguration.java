package com.seonbi.core;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class ClientIdConfiguration implements WebMvcConfigurer {
  public static UUID parse(String raw) {
    if (raw == null
        || !raw.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
      throw new FlowError(
          HttpStatus.BAD_REQUEST, "CLIENT_ID_REQUIRED", "X-Client-Id UUID 헤더가 필요합니다.", Map.of());
    }
    return UUID.fromString(raw);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!request.getMethod().equals("OPTIONS")) parse(request.getHeader("X-Client-Id"));
                return true;
              }
            })
        .addPathPatterns("/api/v1/**");
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(
        new HandlerMethodArgumentResolver() {
          @Override
          public boolean supportsParameter(MethodParameter p) {
            return p.hasParameterAnnotation(ClientId.class) && p.getParameterType() == UUID.class;
          }

          @Override
          public Object resolveArgument(
              MethodParameter p,
              ModelAndViewContainer c,
              NativeWebRequest r,
              org.springframework.web.bind.support.WebDataBinderFactory f) {
            return parse(r.getHeader("X-Client-Id"));
          }
        });
  }
}
