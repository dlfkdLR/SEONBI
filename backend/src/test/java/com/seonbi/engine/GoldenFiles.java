package com.seonbi.engine;

import com.fasterxml.jackson.databind.JsonNode;

/** 다른 패키지의 테스트가 골든 JSON을 읽을 수 있게 공개한 통로. */
public final class GoldenFiles {

  private GoldenFiles() {}

  public static JsonNode load(String name) {
    return Golden.load(name);
  }
}
