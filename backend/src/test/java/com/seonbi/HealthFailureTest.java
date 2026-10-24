package com.seonbi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.seonbi.web.HealthController;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class HealthFailureTest {
  @Test
  void unavailableDatabaseReturnsServiceUnavailable() throws Exception {
    DataSource source = mock(DataSource.class);
    when(source.getConnection()).thenThrow(new SQLException("unavailable"));
    var response = new HealthController(source).health();
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody()).isEqualTo(new HealthController.Health("error", "error"));
  }
}
