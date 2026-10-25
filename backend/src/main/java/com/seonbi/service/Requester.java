package com.seonbi.service;

import com.seonbi.domain.Decision;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 지금 요청을 보낸 사람(X-Client-Id). 결정은 모든 방문자가 함께 쓰므로 AI 한도와 사용량은 결정을 만든 사람이 아니라 요청한 사람에게 붙여야 한다. 요청
 * 밖(테스트·배치)이거나 헤더가 없으면 결정을 만든 사람으로 대신한다.
 */
final class Requester {

  private Requester() {}

  static UUID of(Decision decision) {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes servlet) {
      String header = servlet.getRequest().getHeader("X-Client-Id");
      if (header != null) {
        try {
          return UUID.fromString(header.trim());
        } catch (IllegalArgumentException ignored) {
          // 형식이 틀리면 필터가 이미 막았다. 여기서는 대신할 값을 쓴다
        }
      }
    }
    return decision.getClientId();
  }
}
