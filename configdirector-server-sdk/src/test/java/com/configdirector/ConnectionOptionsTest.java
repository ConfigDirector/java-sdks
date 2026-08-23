package com.configdirector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConnectionOptionsTest {

  private static ConnectionOptions.Builder builder() {
    return ConnectionOptions.builder();
  }

  @Nested
  @DisplayName("defaults")
  class Defaults {

    @Test
    void are_streaming_with_a_minute_between_polls_and_a_three_second_timeout() {
      ConnectionOptions defaults = ConnectionOptions.defaults();

      assertThat(defaults.mode()).isEqualTo(ConnectionMode.STREAMING);
      assertThat(defaults.pollingInterval()).isEqualTo(Duration.ofSeconds(60));
      assertThat(defaults.timeout()).isEqualTo(Duration.ofSeconds(3));
      assertThat(defaults.url()).isNull();
    }

    @Test
    void build_without_any_setting() {
      assertThatNoException().isThrownBy(() -> builder().build());
    }
  }

  @Nested
  @DisplayName("the polling interval")
  class PollingInterval {

    // A negative interval used to reach the transport, which read it as zero and then started no
    // polling thread at all: a client in polling mode would fetch once and never update again,
    // without a word in the log.
    @ParameterizedTest
    @ValueSource(longs = {-60, -1, 0})
    void must_be_positive(long seconds) {
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> builder().pollingInterval(Duration.ofSeconds(seconds)).build())
          .withMessageContaining("pollingInterval")
          .withMessageContaining("positive");
    }

    @Test
    void must_be_short_enough_to_wait_out() {
      // The polling thread waits in nanoseconds, and a longer interval cannot be converted to
      // them: it would die on an ArithmeticException the first time it tried to sleep.
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> builder().pollingInterval(Duration.ofDays(400_000)).build())
          .withMessageContaining("pollingInterval");
    }

    @Test
    void is_not_held_to_the_timeout_ceiling() {
      // The interval never reaches the HTTP client, so what OkHttp accepts does not bind it.
      assertThatNoException()
          .isThrownBy(() -> builder().pollingInterval(Duration.ofDays(30)).build());
    }

    @Test
    void a_positive_interval_is_kept() {
      assertThat(builder().pollingInterval(Duration.ofSeconds(30)).build().pollingInterval())
          .isEqualTo(Duration.ofSeconds(30));
    }
  }

  @Nested
  @DisplayName("the timeout")
  class Timeout {

    @ParameterizedTest
    @ValueSource(longs = {-5, 0})
    void must_be_positive(long seconds) {
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> builder().timeout(Duration.ofSeconds(seconds)).build())
          .withMessageContaining("timeout")
          .withMessageContaining("positive");
    }

    @Test
    void must_be_short_enough_for_the_http_client() {
      // OkHttp refuses anything over Integer.MAX_VALUE milliseconds, and initialization swallows
      // the refusal: the client would simply never become ready.
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> builder().timeout(Duration.ofDays(30)).build())
          .withMessageContaining("longest the HTTP client accepts");
    }

    @Test
    void the_longest_the_http_client_accepts_is_allowed() {
      assertThat(builder().timeout(Duration.ofMillis(Integer.MAX_VALUE)).build().timeout())
          .isEqualTo(Duration.ofMillis(Integer.MAX_VALUE));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> builder().timeout(Duration.ofMillis(Integer.MAX_VALUE + 1L)).build());
    }

    @Test
    void a_positive_timeout_is_kept() {
      assertThat(builder().timeout(Duration.ofMillis(250)).build().timeout())
          .isEqualTo(Duration.ofMillis(250));
    }
  }

  @Nested
  @DisplayName("the URL")
  class Url {

    @ParameterizedTest
    @ValueSource(strings = {"not-a-url", "/relative/path", "configdirector.com", "file:///etc"})
    void must_be_absolute_and_name_a_host(String url) {
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> builder().url(url).build())
          .withMessageContaining(url);
    }

    @Test
    void an_absent_url_means_the_configdirector_service() {
      assertThat(builder().url(null).build().url()).isNull();
      assertThatNoException().isThrownBy(() -> builder().url("  ").build());
    }

    @Test
    void a_usable_url_is_kept_as_it_was_given() {
      assertThat(builder().url("https://proxy.internal:8443/cd").build().url())
          .isEqualTo("https://proxy.internal:8443/cd");
    }
  }

  @Nested
  @DisplayName("null arguments")
  class Nulls {

    @Test
    void are_rejected_where_there_is_no_sensible_default() {
      assertThatExceptionOfType(NullPointerException.class)
          .isThrownBy(() -> builder().mode(null));
      assertThatExceptionOfType(NullPointerException.class)
          .isThrownBy(() -> builder().pollingInterval(null));
      assertThatExceptionOfType(NullPointerException.class)
          .isThrownBy(() -> builder().timeout(null));
    }
  }
}
