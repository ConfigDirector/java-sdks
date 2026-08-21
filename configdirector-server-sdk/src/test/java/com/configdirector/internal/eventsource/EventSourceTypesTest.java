package com.configdirector.internal.eventsource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventSourceTypesTest {

  @Nested
  @DisplayName("EventSourceMessage")
  class Messages {

    @Test
    void requires_data_because_an_event_without_it_is_never_dispatched() {
      assertThatNullPointerException()
          .isThrownBy(() -> new EventSourceMessage(null, "update", "1"))
          .withMessageContaining("data");
    }

    @Test
    void an_unnamed_event_with_no_id_is_allowed() {
      EventSourceMessage message = new EventSourceMessage("payload", null, null);

      assertThat(message.data()).isEqualTo("payload");
      assertThat(message.type()).isNull();
      assertThat(message.id()).isNull();
    }
  }

  @Nested
  @DisplayName("ReconnectionState")
  class Reconnections {

    @Test
    void requires_a_server_delay_because_it_is_the_fallback() {
      assertThatNullPointerException()
          .isThrownBy(() -> new ReconnectionState(1, null, 500, null))
          .withMessageContaining("serverReconnectDelay");
    }

    @Test
    void carries_a_status_without_an_error_and_the_other_way_round() {
      Throwable failure = new StreamClosedException("gone");

      assertThat(new ReconnectionState(1, Duration.ofSeconds(2), 500, null).error()).isNull();
      assertThat(new ReconnectionState(1, Duration.ofSeconds(2), null, failure).status()).isNull();
    }
  }

  @Nested
  @DisplayName("StreamRequest")
  class Requests {

    private static StreamRequest request(Map<String, String> headers) {
      return new StreamRequest(
          "https://example.test", "GET", headers, null, Duration.ZERO, Duration.ZERO, true);
    }

    @Test
    void copies_the_headers_so_a_later_change_does_not_leak_in() {
      Map<String, String> headers = new HashMap<>();
      headers.put("Accept", "text/event-stream");
      StreamRequest streamRequest = request(headers);

      headers.put("Accept", "application/json");

      assertThat(streamRequest.headers()).containsEntry("Accept", "text/event-stream");
    }

    @Test
    void the_headers_are_unmodifiable() {
      StreamRequest streamRequest = request(Map.of("Accept", "text/event-stream"));

      assertThatExceptionOfType(UnsupportedOperationException.class)
          .isThrownBy(() -> streamRequest.headers().put("X", "y"));
    }

    @Test
    void requires_the_fields_the_transport_cannot_default() {
      assertThatNullPointerException()
          .isThrownBy(
              () ->
                  new StreamRequest(
                      null, "GET", Map.of(), null, Duration.ZERO, Duration.ZERO, true))
          .withMessageContaining("url");
      assertThatNullPointerException()
          .isThrownBy(
              () ->
                  new StreamRequest(
                      "https://example.test", "GET", Map.of(), null, null, Duration.ZERO, true))
          .withMessageContaining("connectTimeout");
      assertThatNullPointerException()
          .isThrownBy(
              () ->
                  new StreamRequest(
                      "https://example.test", "GET", Map.of(), null, Duration.ZERO, null, true))
          .withMessageContaining("readTimeout");
    }
  }
}
