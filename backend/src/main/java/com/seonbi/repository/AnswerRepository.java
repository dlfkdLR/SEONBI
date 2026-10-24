package com.seonbi.repository;

import com.seonbi.domain.Answer;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnswerRepository extends JpaRepository<Answer, UUID> {}
