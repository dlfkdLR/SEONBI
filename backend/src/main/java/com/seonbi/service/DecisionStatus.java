package com.seonbi.service;

/** 결정 상태 (개발기획서 4.2). DB에는 이 이름 그대로 문자열로 저장한다. */
public enum DecisionStatus {
  DRAFT,
  QUESTIONING,
  ANALYZED,
  FINALIZED
}
