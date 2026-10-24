package com.seonbi;

import static org.assertj.core.api.Assertions.assertThat;

import com.seonbi.dto.CRequests;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CRequestsValidationTest {
  private static jakarta.validation.ValidatorFactory validatorFactory;
  private static Validator validator;

  @BeforeAll
  static void createValidator() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    validator = validatorFactory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    validatorFactory.close();
  }

  @Test
  void confirmAllowsOmittedConfidenceAndAcceptsSpecificationLimits() {
    var criterion =
        new CRequests.CriterionItem(
            "criterion",
            "기준".repeat(20),
            "rating",
            null,
            "단위".repeat(10),
            List.of("낮음", "보통", "높음"),
            null,
            null);

    assertThat(validator.validate(new CRequests.ConfirmInput(List.of(criterion)))).isEmpty();
  }

  @Test
  void confirmRejectsValuesOverSpecificationLimits() {
    var criterion =
        new CRequests.CriterionItem(
            "criterion",
            "기준".repeat(21),
            "rating",
            null,
            "단위".repeat(11),
            List.of("낮음", "보통"),
            null,
            null);
    var condition =
        new CRequests.ConstraintItem(
            "constraint", "required_tag", null, null, null, "조건".repeat(31), null, null);

    assertThat(validator.validate(new CRequests.ConfirmInput(List.of(criterion, condition))))
        .hasSize(4);
  }
}
