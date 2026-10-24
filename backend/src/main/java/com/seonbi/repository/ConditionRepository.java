package com.seonbi.repository;

import com.seonbi.domain.Condition;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConditionRepository extends JpaRepository<Condition, UUID> {}
