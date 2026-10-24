package com.seonbi.repository;

import com.seonbi.domain.AnalysisResult;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, UUID> {}
