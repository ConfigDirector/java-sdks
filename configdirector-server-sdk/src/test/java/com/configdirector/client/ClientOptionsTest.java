package com.configdirector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.ConnectionMode;
import com.configdirector.ConnectionOptions;
import com.configdirector.TelemetryOptions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ClientOptionsTest {

  @Nested
  @DisplayName("building a client")
  class Building {

    @Test
    void the_one_argument_form_needs_no_configuration() {
      try (ConfigDirectorClient client = ConfigDirector.client("sdk-key")) {
        assertThat(client.isClosed()).isFalse();
        assertThat(client.isReady()).isFalse();
      }
    }

    @Test
    void the_lambda_receives_the_options_to_adjust() {
      AtomicBoolean configured = new AtomicBoolean();

      try (ConfigDirectorClient client =
          ConfigDirector.client(
              "sdk-key",
              options -> {
                configured.set(true);
                options.metadata("checkout", "1.2.3");
              })) {
        assertThat(configured).isTrue();
        assertThat(client).isNotNull();
      }
    }

    @Test
    void the_setters_chain() {
      try (ConfigDirectorClient client =
          ConfigDirector.client(
              "sdk-key",
              options ->
                  options
                      .metadata("checkout", "1.2.3")
                      .logger(LoggerFactory.getLogger("test"))
                      .connection(connection -> connection.mode(ConnectionMode.POLLING)))) {
        assertThat(client).isNotNull();
      }
    }

    @Test
    void connection_settings_can_be_supplied_ready_built_for_reuse() {
      ConnectionOptions shared =
          ConnectionOptions.builder()
              .mode(ConnectionMode.POLLING)
              .pollingInterval(Duration.ofMinutes(2))
              .build();

      try (ConfigDirectorClient first = ConfigDirector.client("k", options -> options.connection(shared));
          ConfigDirectorClient second =
              ConfigDirector.client("k", options -> options.connection(shared))) {
        assertThat(first).isNotSameAs(second);
      }
    }

    @Test
    void the_nested_connection_lambda_chains_too() {
      try (ConfigDirectorClient client =
          ConfigDirector.client(
              "sdk-key",
              options ->
                  options.connection(
                      connection ->
                          connection
                              .mode(ConnectionMode.POLLING)
                              .pollingInterval(Duration.ofMinutes(2))
                              .timeout(Duration.ofSeconds(5))
                              .url("https://proxy.test")))) {
        assertThat(client).isNotNull();
      }
    }
  }

  @Nested
  @DisplayName("telemetry settings")
  class Telemetry {

    @Test
    void are_left_at_their_defaults_when_untouched() {
      TelemetryOptions defaults = TelemetryOptions.defaults();

      assertThat(defaults.eventQueueLimit()).isEqualTo(TelemetryOptions.DEFAULT_EVENT_QUEUE_LIMIT);
      assertThat(defaults.flushInterval()).isEqualTo(TelemetryOptions.DEFAULT_FLUSH_INTERVAL);
    }

    @Test
    void can_be_tuned_through_the_nested_lambda() {
      try (ConfigDirectorClient client =
          ConfigDirector.client(
              "sdk-key",
              options ->
                  options.telemetry(
                      telemetry ->
                          telemetry.eventQueueLimit(200).flushInterval(Duration.ofSeconds(5))))) {
        assertThat(client).isNotNull();
      }
    }

    @Test
    void can_be_supplied_ready_built_for_reuse() {
      TelemetryOptions shared = TelemetryOptions.builder().eventQueueLimit(200).build();

      try (ConfigDirectorClient first = ConfigDirector.client("k", options -> options.telemetry(shared));
          ConfigDirectorClient second =
              ConfigDirector.client("k", options -> options.telemetry(shared))) {
        assertThat(first).isNotSameAs(second);
      }
    }

    @Test
    void an_event_queue_limit_out_of_range_is_rejected() {
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> TelemetryOptions.builder().eventQueueLimit(99).build())
          .withMessageContaining("between 100 and 100000");
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> TelemetryOptions.builder().eventQueueLimit(100_001).build());
    }

    @Test
    void a_flush_interval_that_is_not_positive_is_rejected() {
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> TelemetryOptions.builder().flushInterval(Duration.ZERO).build());
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> TelemetryOptions.builder().flushInterval(Duration.ofSeconds(-1)).build());
    }

    @Test
    void the_bounds_are_inclusive() {
      assertThat(TelemetryOptions.builder().eventQueueLimit(100).build().eventQueueLimit())
          .isEqualTo(100);
      assertThat(TelemetryOptions.builder().eventQueueLimit(100_000).build().eventQueueLimit())
          .isEqualTo(100_000);
    }
  }

  @Nested
  @DisplayName("rejections")
  class Rejections {

    @Test
    void a_blank_sdk_key_is_rejected_by_either_form() {
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> ConfigDirector.client(""));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> ConfigDirector.client("  ", options -> {}));
    }

    @Test
    void a_null_configure_lambda_is_rejected() {
      assertThatNullPointerException().isThrownBy(() -> ConfigDirector.client("k", null));
    }

    @Test
    void null_settings_are_rejected() {
      assertThatNullPointerException()
          .isThrownBy(() -> ConfigDirector.client("k", options -> options.metadata(null)));
      assertThatNullPointerException()
          .isThrownBy(() -> ConfigDirector.client("k", options -> options.logger(null)));
      assertThatNullPointerException()
          .isThrownBy(
              () -> ConfigDirector.client("k", options -> options.connection((ConnectionOptions) null)));
      assertThatNullPointerException()
          .isThrownBy(
              () -> ConfigDirector.client("k", options -> options.telemetry((TelemetryOptions) null)));
    }

    @Test
    void an_exception_from_the_lambda_reaches_the_caller() {
      assertThatExceptionOfType(IllegalStateException.class)
          .isThrownBy(
              () ->
                  ConfigDirector.client(
                      "k",
                      options -> {
                        throw new IllegalStateException("bad configuration");
                      }));
    }
  }
}
