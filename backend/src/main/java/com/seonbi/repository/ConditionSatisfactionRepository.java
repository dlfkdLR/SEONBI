package com.seonbi.repository;

import com.seonbi.domain.ConditionSatisfaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConditionSatisfactionRepository
    extends JpaRepository<ConditionSatisfaction, ConditionSatisfaction.Key> {}
