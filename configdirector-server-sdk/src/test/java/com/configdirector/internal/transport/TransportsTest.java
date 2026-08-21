package com.configdirector.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TransportsTest {

  @Nested
  @DisplayName("resolving the endpoint")
  class Resolving {

    @ParameterizedTest
    @CsvSource({
      "https://api.test, server/polling/v1, https://api.test/server/polling/v1",
      "https://api.test/, server/polling/v1, https://api.test/server/polling/v1",
    })
    void appends_the_path_to_the_base(String base, String path, String expected) {
      assertThat(Transports.resolve(base, path)).isEqualTo(expected);
    }

    @Test
    void keeps_every_segment_of_a_proxy_base_url() {
      // Without the trailing slash, resolve would treat "configdirector" as a file name and drop
      // it, silently pointing the SDK at the wrong host path.
      assertThat(Transports.resolve("https://proxy.test/configdirector", "server/polling/v1"))
          .isEqualTo("https://proxy.test/configdirector/server/polling/v1");
    }
  }

  @Nested
  @DisplayName("the request body")
  class Body {

    private static String bodyOf(Map<String, Object> payload) {
      return new String(Transports.jsonBody(payload), StandardCharsets.UTF_8);
    }

    @Test
    void carries_the_key_and_the_meta_context() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("serverSdkKey", "sdk-123");
      payload.put("metaContext", Map.of("sdkName", "java-server-sdk"));

      assertThat(bodyOf(payload))
          .contains("\"serverSdkKey\":\"sdk-123\"")
          .contains("\"sdkName\":\"java-server-sdk\"");
    }

    @Test
    void omits_null_values_rather_than_sending_json_null() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("serverSdkKey", "sdk-123");
      payload.put("lastUpdateTimestamp", null);

      assertThat(bodyOf(payload)).doesNotContain("lastUpdateTimestamp");
    }

    @Test
    void includes_a_timestamp_once_there_is_one() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("lastUpdateTimestamp", "2026-01-01T00:00:00Z");

      assertThat(bodyOf(payload)).contains("\"lastUpdateTimestamp\":\"2026-01-01T00:00:00Z\"");
    }
  }

  @Nested
  @DisplayName("fatal statuses")
  class FatalStatuses {

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 499})
    void a_4xx_is_unrecoverable(int status) {
      assertThat(Transports.isFatalStatus(status)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 301, 500, 502, 503})
    void anything_else_is_worth_retrying(int status) {
      assertThat(Transports.isFatalStatus(status)).isFalse();
    }

    @Test
    void an_absent_status_is_worth_retrying() {
      assertThat(Transports.isFatalStatus(null)).isFalse();
    }

    @Test
    void the_error_names_the_status_and_says_it_will_not_retry() {
      ConfigDirectorConnectionException error = Transports.fatalStatusError(401, "Unauthorized");

      assertThat(error.status()).isEqualTo(401);
      assertThat(error.getMessage())
          .contains("401")
          .contains("Unauthorized")
          .contains("retry attempts will be ignored");
    }

    @Test
    void a_blank_detail_is_left_out() {
      assertThat(Transports.fatalStatusError(403, "   ").getMessage()).doesNotContain("(");
      assertThat(Transports.fatalStatusError(403, null).getMessage()).doesNotContain("(");
    }

    @Test
    void an_unknown_status_still_reads_sensibly() {
      assertThat(Transports.fatalStatusError(null, null).getMessage()).contains("unknown");
    }
  }

  @Nested
  @DisplayName("headers")
  class Headers {

    @Test
    void identify_the_sdk_so_bot_protection_does_not_reject_the_request() {
      assertThat(Transports.REQUEST_HEADERS)
          .containsEntry("Content-Type", "application/json")
          .hasEntrySatisfying(
              "User-Agent", agent -> assertThat(agent).startsWith("java-server-sdk/"));
    }
  }
}
