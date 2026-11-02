package com.seonbi.repository;

import com.seonbi.domain.Decision;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {
  java.util.Optional<Decision> findByIdAndDeletedAtIsNull(UUID id);

  /** 초기 설정 완료 요청의 멱등 키로 이미 만든 결정을 찾는다(삭제된 결정도 포함). */
  java.util.Optional<Decision> findByClientIdAndSetupKey(UUID clientId, UUID setupKey);
}
