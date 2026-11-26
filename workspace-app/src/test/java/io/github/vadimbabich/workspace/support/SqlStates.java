package io.github.vadimbabich.workspace.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.R2dbcException;
import java.util.function.Consumer;
import org.springframework.core.NestedExceptionUtils;

public final class SqlStates {

  public static final String INSUFFICIENT_PRIVILEGE = "42501";
  public static final String FOREIGN_KEY_VIOLATION = "23503";
  public static final String UNIQUE_VIOLATION = "23505";
  public static final String CHECK_VIOLATION = "23514";

  private SqlStates() {
  }

  public static Consumer<Throwable> refusedWith(String expectedSqlState) {
    return refusal -> {
      Throwable databaseError = NestedExceptionUtils.getMostSpecificCause(refusal);

      assertThat(databaseError).isInstanceOf(R2dbcException.class);
      assertThat(((R2dbcException) databaseError).getSqlState()).isEqualTo(expectedSqlState);
    };
  }
}
