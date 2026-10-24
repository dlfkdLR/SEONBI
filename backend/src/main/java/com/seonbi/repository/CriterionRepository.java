package com.seonbi.repository;

import com.seonbi.domain.Criterion;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CriterionRepository extends JpaRepository<Criterion, UUID> {}
