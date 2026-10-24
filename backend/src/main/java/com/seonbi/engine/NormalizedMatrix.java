package com.seonbi.engine;

import java.util.List;

/** 제외되지 않은 선택지 × 기준의 0~1 값(클수록 좋음). */
public record NormalizedMatrix(List<String> optionIds, List<String> criterionIds, double[][] values) {}
