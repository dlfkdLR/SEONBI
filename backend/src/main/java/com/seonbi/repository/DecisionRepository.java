package com.seonbi.repository;

import com.seonbi.domain.Decision;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {
  java.util.Optional<Decision> findByIdAndClientIdAndDeletedAtIsNull(UUID id, UUID clientId);
}
