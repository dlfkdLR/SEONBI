package com.seonbi.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptsTest {

  // 분석 결과에는 아직 없는 값(revealedRank = null)이 들어 있다. 이 값 때문에 프롬프트 만들기가 터지면 안 된다.
  @Test
  void nullValuesInDataBlockAreKept() {
    Map<String, Object> weight = new HashMap<>();
    weight.put("statedRank", 1);
    weight.put("revealedRank", null);
    String block = Prompts.dataBlock(Map.of("weights", List.of(weight)));
    assertTrue(
        block.contains("\"revealedRank\":null") || block.contains("\"revealedRank\" : null"));
  }
}
