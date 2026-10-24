package com.seonbi.repository;

import com.seonbi.domain.FinalChoice;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinalChoiceRepository extends JpaRepository<FinalChoice, UUID> {}
