package com.configdirector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.ConnectionMode;
import com.configdirector.ConnectionOptions;
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
                      .connection(connection -> connection.mode(ConnectionMode.ONE_TIME)))) {
        assertThat(client).isNotNull();
      }
    }

    @Test
    void connection_settings_can_be_supplied_ready_built_for_reuse() {
      ConnectionOptions shared =
          ConnectionOptions.builder()
              .mode(ConnectionMode.POLLING)
              .pollingInterval(Duration.ofSeconds(30))
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
                              .pollingInterval(Duration.ofSeconds(30))
                              .timeout(Duration.ofSeconds(5))
                              .url("https://proxy.test")))) {
        assertThat(client).isNotNull();
      }
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
