package com.seonbi.repository;

import com.seonbi.domain.EstimateSnapshot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstimateSnapshotRepository extends JpaRepository<EstimateSnapshot, UUID> {}
