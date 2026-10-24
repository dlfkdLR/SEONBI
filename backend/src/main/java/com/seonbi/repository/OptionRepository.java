package com.seonbi.repository;

import com.seonbi.domain.Option;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OptionRepository extends JpaRepository<Option, UUID> {}
