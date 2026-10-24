package com.seonbi.repository;

import com.seonbi.domain.OptionValue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OptionValueRepository extends JpaRepository<OptionValue, OptionValue.Key> {}
