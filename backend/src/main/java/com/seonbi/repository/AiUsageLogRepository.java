package com.seonbi.repository;

import com.seonbi.domain.AiUsageLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiUsageLogRepository extends JpaRepository<AiUsageLog, UUID> {}
