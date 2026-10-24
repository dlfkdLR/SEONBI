package com.seonbi.ai.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** 실제 네트워크 호출을 바꿔 끼울 수 있는 자리. 테스트는 가짜를 넣고, 운영에서는 JDK HttpClient를 쓴다. */
@FunctionalInterface
public interface HttpTransport {

  /** HTTP 응답. */
  record Result(int status, String body) {}

  Result post(URI uri, Map<String, String> headers, String jsonBody, Duration timeout)
      throws IOException, InterruptedException;

  /** 기본 구현: JDK 내장 HttpClient. */
  static HttpTransport jdk() {
    return (uri, headers, body, timeout) -> {
      HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
      HttpRequest.Builder request =
          HttpRequest.newBuilder(uri)
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body));
      headers.forEach(request::header);
      HttpResponse<String> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Result(response.statusCode(), response.body());
    };
  }
}
