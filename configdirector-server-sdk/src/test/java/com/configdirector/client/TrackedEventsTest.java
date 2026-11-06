package com.configdirector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConnectionMode;
import com.configdirector.Context;
import com.configdirector.TrackOptions;
import com.configdirector.testing.TestHttpServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TrackedEventsTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final AtomicInteger LOGGERS = new AtomicInteger();
  private static final String BUNDLE = "{\"timestamp\":\"t1\",\"configs\":{}}";

  private final List<String> telemetryBodies = Collections.synchronizedList(new ArrayList<>());

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> appender;
  private ConfigDirectorClient client;
  private TestHttpServer server;

  @BeforeEach
  void attachAppender() {
    logger =
        (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger("tracked-events-under-test-" + LOGGERS.incrementAndGet());
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    if (server != null) {
      server.close();
    }
    logger.detachAppender(appender);
  }

  private void startServer() {
    server =
        start(
            session -> {
              if (session.path().endsWith("/server/telemetry/v1")) {
                telemetryBodies.add(session.bodyAsString());
                session.respond(200, "Content-Length: 0", "Connection: close");
              } else {
                byte[] body = BUNDLE.getBytes(StandardCharsets.UTF_8);
                session.respond(
                    200,
                    "Content-Type: application/json",
                    "Content-Length: " + body.length,
                    "Connection: close");
                session.send(BUNDLE);
              }
              session.close();
            });
  }

  private void startReadyClient() {
    startServer();
    String url = server.url("/");
    client =
        ConfigDirector.client(
            "sdk-key",
            options ->
                options
                    .logger(logger)
                    .connection(
                        connection ->
                            connection.mode(ConnectionMode.POLLING).url(url).timeout(TIMEOUT)));
    client.initialize();
  }

  private static TestHttpServer start(Consumer<TestHttpServer.Session> handler) {
    try {
      return TestHttpServer.start(handler);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private String rawReportSentOnClosing() {
    client.close();
    await().atMost(TIMEOUT).until(() -> !telemetryBodies.isEmpty());
    synchronized (telemetryBodies) {
      return telemetryBodies.get(0);
    }
  }

  private JsonArray trackedEventsSentOnClosing() {
    return json(rawReportSentOnClosing())
        .getAsJsonObject("discreteEvents")
        .getAsJsonArray("trackedEvents");
  }

  private List<String> trackedEventNamesSentOnClosing() {
    List<String> names = new ArrayList<>();
    for (JsonElement entry : trackedEventsSentOnClosing()) {
      names.add(entry.getAsJsonObject().get("name").getAsString());
    }
    return names;
  }

  private JsonObject firstTrackedEventSentOnClosing() {
    return trackedEventsSentOnClosing().get(0).getAsJsonObject();
  }

  private JsonObject firstTrackedEventWithoutItsTimeSentOnClosing() {
    JsonObject entry = firstTrackedEventSentOnClosing();
    entry.remove("happenedAt");
    return entry;
  }

  private static JsonObject json(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  private List<String> messagesLoggedAt(Level level) {
    return appender.list.stream()
        .filter(event -> event.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private List<String> errors() {
    return messagesLoggedAt(Level.ERROR);
  }

  private List<String> warnings() {
    return messagesLoggedAt(Level.WARN);
  }

  @Test
  void sends_an_event_tracked_for_a_context_on_the_next_telemetry_batch_with_its_value_and_details() {
    startReadyClient();

    client.track(
        "checkout-completed",
        Context.builder().id("user-1").build(),
        TrackOptions.builder()
            .value(49.9)
            .details(
                Map.of(
                    "plan", "pro",
                    "seats", 3,
                    "trial", false,
                    "items", List.of(Map.of("sku", "book"))))
            .build());

    assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
        .isEqualTo(
            json(
                "{\"name\":\"checkout-completed\",\"contextKind\":\"user\","
                    + "\"contextIdentifier\":\"user-1\",\"value\":49.9,\"details\":{\"plan\":\"pro\","
                    + "\"seats\":3,\"trial\":false,\"items\":[{\"sku\":\"book\"}]}}"));
  }

  @Test
  void stamps_an_event_with_the_time_track_was_called_rather_than_the_time_it_was_sent()
      throws InterruptedException {
    startReadyClient();
    Instant beforeTracking = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    client.track("checkout-completed");
    Instant afterTracking = Instant.now();
    Thread.sleep(50);

    Instant happenedAt =
        Instant.parse(firstTrackedEventSentOnClosing().get("happenedAt").getAsString());
    assertThat(happenedAt).isBetween(beforeTracking, afterTracking);
  }

  @Test
  void sends_an_event_tracked_without_a_context_with_no_context_kind_or_identifier_and_logs_nothing() {
    startReadyClient();

    client.track("nightly-import-finished");

    assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
        .isEqualTo(json("{\"name\":\"nightly-import-finished\"}"));
    assertThat(warnings()).isEmpty();
    assertThat(errors()).isEmpty();
  }

  @Test
  void sends_an_event_tracked_for_a_null_context_with_its_value() {
    startReadyClient();

    client.track("nightly-import-finished", null, TrackOptions.builder().value(3).build());

    assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
        .isEqualTo(json("{\"name\":\"nightly-import-finished\",\"value\":3}"));
  }

  @Test
  void sends_an_event_tracked_for_a_context_without_an_id_with_no_context_kind_or_identifier_and_logs_nothing() {
    startReadyClient();

    client.track("checkout-completed", Context.builder().kind("organization").name("Acme").build());

    assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
        .isEqualTo(json("{\"name\":\"checkout-completed\"}"));
    assertThat(warnings()).isEmpty();
    assertThat(errors()).isEmpty();
  }

  @Test
  void sends_an_event_tracked_for_a_context_with_a_blank_id_with_no_context_kind_or_identifier() {
    startReadyClient();

    client.track("checkout-completed", Context.builder().id("  ").build());

    assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
        .isEqualTo(json("{\"name\":\"checkout-completed\"}"));
  }

  @Test
  void sends_the_kind_of_the_context_an_event_was_tracked_for() {
    startReadyClient();

    client.track("seat-added", Context.builder().id("acme").kind("organization").build());

    assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
        .isEqualTo(
            json(
                "{\"name\":\"seat-added\",\"contextKind\":\"organization\","
                    + "\"contextIdentifier\":\"acme\"}"));
  }

  @Test
  void withholds_the_identifier_of_an_anonymous_context_from_the_event_tracked_for_it() {
    startReadyClient();

    client.track("checkout-completed", Context.builder().id("visitor-7").anonymous(true).build());

    assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
        .isEqualTo(json("{\"name\":\"checkout-completed\"}"));
  }

  @Test
  void logs_the_invalid_kind_of_a_context_as_evaluations_do_and_sends_the_event_without_a_context() {
    startReadyClient();

    client.track("checkout-completed", Context.builder().id("acme").kind("Tenant").build());

    assertThat(errors()).hasSize(1);
    assertThat(errors().get(0)).contains("'Tenant'");
    assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
        .isEqualTo(json("{\"name\":\"checkout-completed\"}"));
  }

  @Test
  void sends_the_event_in_the_report_that_carries_the_invalid_kind_of_its_context() {
    startReadyClient();

    client.track("ft-probe-event", Context.builder().id("user-1").build());
    client.getString("greeting", "fallback", Context.builder().id("user-1").kind("Tenant").build());

    JsonObject report = json(rawReportSentOnClosing());
    assertThat(telemetryBodies).hasSize(1);
    assertThat(report.getAsJsonObject("discreteEvents").getAsJsonArray("trackedEvents")).hasSize(1);
    assertThat(report.getAsJsonObject("aggregatedEvents").getAsJsonArray("invalidContext")).hasSize(1);
  }

  @Test
  void sends_nothing_for_an_event_tracked_in_offline_mode() {
    startServer();
    String url = server.url("/");
    client =
        ConfigDirector.client(
            options ->
                options
                    .logger(logger)
                    .connection(connection -> connection.mode(ConnectionMode.OFFLINE).url(url)));
    client.initialize();

    client.track("checkout-completed", Context.builder().id("user-1").build());
    client.close();

    assertThat(telemetryBodies).isEmpty();
  }

  @Test
  void logs_nothing_for_an_event_tracked_in_offline_mode_that_would_otherwise_be_logged() {
    client =
        ConfigDirector.client(
            options ->
                options
                    .logger(logger)
                    .connection(connection -> connection.mode(ConnectionMode.OFFLINE)));
    client.initialize();

    client.track("", Context.builder().id("acme").kind("Tenant").build());
    client.close();

    assertThat(warnings()).isEmpty();
    assertThat(errors()).isEmpty();
  }

  @Test
  void sends_nothing_and_logs_nothing_for_an_event_tracked_after_close() {
    startReadyClient();
    client.close();

    client.track("", Context.builder().id("acme").kind("Tenant").build());

    assertThat(telemetryBodies).isEmpty();
    assertThat(warnings()).isEmpty();
    assertThat(errors()).isEmpty();
  }

  @Test
  void logs_each_cause_of_one_batch_separately() {
    startReadyClient();

    client.track("");
    client.track("checkout-completed", null, TrackOptions.builder().value(Double.NaN).build());
    client.track(
        "checkout-completed",
        null,
        TrackOptions.builder().details(Map.of("at", Instant.EPOCH)).build());

    assertThat(warnings()).hasSize(3);
  }

  @Nested
  @DisplayName("event names")
  class EventNames {

    @Test
    void sends_an_event_whose_name_has_150_characters() {
      startReadyClient();

      client.track("n".repeat(150));

      assertThat(trackedEventNamesSentOnClosing()).containsExactly("n".repeat(150));
      assertThat(warnings()).isEmpty();
    }

    @Test
    void counts_a_character_outside_the_basic_plane_as_one_character_of_the_name() {
      startReadyClient();

      client.track("😀".repeat(150));

      assertThat(trackedEventNamesSentOnClosing()).containsExactly("😀".repeat(150));
      assertThat(warnings()).isEmpty();
    }

    @Test
    void does_not_send_an_event_whose_name_has_151_characters_and_logs_a_warning() {
      startReadyClient();

      client.track("n".repeat(151));
      client.track("checkout-completed");

      assertThat(trackedEventNamesSentOnClosing()).containsExactly("checkout-completed");
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void does_not_send_an_event_whose_name_is_empty_and_logs_a_warning() {
      startReadyClient();

      client.track("");
      client.track("checkout-completed");

      assertThat(trackedEventNamesSentOnClosing()).containsExactly("checkout-completed");
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void does_not_send_an_event_whose_name_is_null_without_throwing() {
      startReadyClient();

      client.track(null);
      client.track("checkout-completed");

      assertThat(trackedEventNamesSentOnClosing()).containsExactly("checkout-completed");
      assertThat(warnings()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("values")
  class Values {

    private static final String EVENT_WITHOUT_ITS_VALUE =
        "{\"name\":\"checkout-completed\",\"details\":{\"plan\":\"pro\"}}";

    @Test
    void sends_a_double_with_every_digit_it_was_given() {
      startReadyClient();

      client.track("checkout-completed", null, TrackOptions.builder().value(1234567.891).build());

      assertThat(firstTrackedEventSentOnClosing().get("value").getAsString())
          .isEqualTo("1234567.891");
    }

    @Test
    void sends_a_whole_double_as_a_whole_number() {
      startReadyClient();

      client.track("checkout-completed", null, TrackOptions.builder().value(3.0).build());

      assertThat(firstTrackedEventSentOnClosing().get("value").getAsString()).isEqualTo("3");
    }

    @Test
    void sends_a_value_of_zero() {
      startReadyClient();

      client.track("checkout-completed", null, TrackOptions.builder().value(0).build());

      assertThat(firstTrackedEventSentOnClosing().get("value").getAsString()).isEqualTo("0");
    }

    @Test
    void sends_a_long_beyond_what_a_double_holds_without_rounding_it() {
      startReadyClient();

      client.track(
          "checkout-completed", null, TrackOptions.builder().value(Long.MAX_VALUE).build());

      assertThat(firstTrackedEventSentOnClosing().get("value").getAsString())
          .isEqualTo("9223372036854775807");
    }

    @Test
    void sends_a_big_integer_beyond_what_a_long_holds_without_rounding_it() {
      startReadyClient();

      client.track(
          "checkout-completed",
          null,
          TrackOptions.builder().value(new BigInteger("123456789012345678901234567890")).build());

      assertThat(firstTrackedEventSentOnClosing().get("value").getAsString())
          .isEqualTo("123456789012345678901234567890");
    }

    @Test
    void sends_a_big_decimal_with_more_digits_than_a_double_holds_without_rounding_it() {
      startReadyClient();

      client.track(
          "checkout-completed",
          null,
          TrackOptions.builder().value(new BigDecimal("1234567.890123456789012345678")).build());

      assertThat(firstTrackedEventSentOnClosing().get("value").getAsString())
          .isEqualTo("1234567.890123456789012345678");
    }

    @Test
    void sends_a_big_decimal_with_an_exponent_as_a_json_number() {
      startReadyClient();

      client.track(
          "checkout-completed", null, TrackOptions.builder().value(new BigDecimal("1E+3")).build());

      assertThat(firstTrackedEventSentOnClosing().get("value").getAsString()).isEqualTo("1E+3");
    }

    @Test
    void sends_a_float_as_it_was_written() {
      startReadyClient();

      client.track("checkout-completed", null, TrackOptions.builder().value(0.1f).build());

      assertThat(firstTrackedEventSentOnClosing().get("value").getAsString()).isEqualTo("0.1");
    }

    @Test
    void sends_the_number_another_number_type_holds() {
      startReadyClient();

      client.track(
          "checkout-completed", null, TrackOptions.builder().value(new AtomicLong(42)).build());

      assertThat(firstTrackedEventSentOnClosing().get("value").getAsString()).isEqualTo("42");
    }

    @Test
    void leaves_out_a_double_that_is_not_a_number_sends_the_event_and_logs_a_warning() {
      startReadyClient();

      client.track(
          "checkout-completed",
          null,
          TrackOptions.builder().value(Double.NaN).details(Map.of("plan", "pro")).build());

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_VALUE));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void leaves_out_an_infinite_double_sends_the_event_and_logs_a_warning() {
      startReadyClient();

      client.track(
          "checkout-completed",
          null,
          TrackOptions.builder()
              .value(Double.POSITIVE_INFINITY)
              .details(Map.of("plan", "pro"))
              .build());

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_VALUE));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void leaves_out_an_infinite_float_sends_the_event_and_logs_a_warning() {
      startReadyClient();

      client.track(
          "checkout-completed",
          null,
          TrackOptions.builder()
              .value(Float.NEGATIVE_INFINITY)
              .details(Map.of("plan", "pro"))
              .build());

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_VALUE));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void leaves_out_a_number_type_whose_text_is_not_a_number_sends_the_event_and_logs_a_warning() {
      startReadyClient();

      client.track(
          "checkout-completed",
          null,
          TrackOptions.builder()
              .value(new NumberWithText("many"))
              .details(Map.of("plan", "pro"))
              .build());

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_VALUE));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void leaves_out_a_number_type_whose_text_cannot_be_read_sends_the_event_and_logs_a_warning() {
      startReadyClient();

      client.track(
          "checkout-completed",
          null,
          TrackOptions.builder()
              .value(new NumberWithText(null))
              .details(Map.of("plan", "pro"))
              .build());

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_VALUE));
      assertThat(warnings()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("details")
  class Details {

    private static final String EVENT_WITHOUT_ITS_DETAILS =
        "{\"name\":\"checkout-completed\",\"contextKind\":\"user\","
            + "\"contextIdentifier\":\"user-1\",\"value\":49.9}";

    private Map<String, Object> detailsWithKeys(int count) {
      Map<String, Object> details = new LinkedHashMap<>();
      for (int number = 1; number <= count; number++) {
        details.put("key-" + number, number);
      }
      return details;
    }

    private void trackWithDetails(Map<String, Object> details) {
      client.track(
          "checkout-completed",
          Context.builder().id("user-1").build(),
          TrackOptions.builder().value(49.9).details(details).build());
    }

    private JsonObject detailsSentOnClosing() {
      return firstTrackedEventSentOnClosing().getAsJsonObject("details");
    }

    @Test
    void sends_empty_details() {
      startReadyClient();

      trackWithDetails(Map.of());

      assertThat(detailsSentOnClosing()).isEqualTo(json("{}"));
      assertThat(warnings()).isEmpty();
    }

    @Test
    void sends_details_with_20_top_level_keys() {
      startReadyClient();

      trackWithDetails(detailsWithKeys(20));

      assertThat(detailsSentOnClosing().size()).isEqualTo(20);
      assertThat(warnings()).isEmpty();
    }

    @Test
    void leaves_out_details_with_21_top_level_keys_sends_the_event_and_logs_a_warning() {
      startReadyClient();

      trackWithDetails(detailsWithKeys(21));

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_DETAILS));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void sends_details_of_exactly_1024_bytes_as_json() {
      startReadyClient();

      trackWithDetails(Map.of("note", "a".repeat(1_013)));

      assertThat(detailsSentOnClosing().get("note").getAsString()).isEqualTo("a".repeat(1_013));
      assertThat(warnings()).isEmpty();
    }

    @Test
    void leaves_out_details_of_1025_bytes_as_json_sends_the_event_and_logs_a_warning() {
      startReadyClient();

      trackWithDetails(Map.of("note", "a".repeat(1_014)));

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_DETAILS));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void writes_details_of_exactly_1024_bytes_on_the_wire_without_escaping_non_ascii_text() {
      startReadyClient();

      trackWithDetails(Map.of("note", "é".repeat(506) + "a"));

      assertThat(rawReportSentOnClosing())
          .contains("\"details\":{\"note\":\"" + "é".repeat(506) + "a\"},");
      assertThat(warnings()).isEmpty();
    }

    @Test
    void measures_details_in_bytes_so_507_two_byte_characters_are_left_out() {
      startReadyClient();

      trackWithDetails(Map.of("note", "é".repeat(507)));

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_DETAILS));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void writes_details_on_the_wire_without_spaces_between_their_parts() {
      startReadyClient();
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("plan", "pro");
      details.put("items", List.of(1, 2));

      trackWithDetails(details);

      assertThat(rawReportSentOnClosing())
          .contains("\"details\":{\"plan\":\"pro\",\"items\":[1,2]},");
    }

    @Test
    void sends_details_holding_as_many_values_as_fit_in_1024_bytes_as_json() {
      startReadyClient();

      trackWithDetails(Map.of("a", Collections.nCopies(508, 1)));

      assertThat(detailsSentOnClosing().getAsJsonArray("a")).hasSize(508);
      assertThat(warnings()).isEmpty();
    }

    @Test
    void leaves_out_details_holding_one_value_more_than_fits_in_1024_bytes_as_json() {
      startReadyClient();

      trackWithDetails(Map.of("a", Collections.nCopies(509, 1)));

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_DETAILS));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void sends_strings_numbers_booleans_nulls_and_nested_objects_and_lists() {
      startReadyClient();
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("plan", "pro");
      details.put("seats", 3);
      details.put("price", new BigDecimal("49.90"));
      details.put("trial", false);
      details.put("coupon", null);
      details.put("items", List.of(Map.of("sku", "book")));

      trackWithDetails(details);

      assertThat(detailsSentOnClosing())
          .isEqualTo(
              json(
                  "{\"plan\":\"pro\",\"seats\":3,\"price\":49.90,\"trial\":false,"
                      + "\"coupon\":null,\"items\":[{\"sku\":\"book\"}]}"));
      assertThat(warnings()).isEmpty();
    }

    @Test
    void sends_details_held_in_any_map_and_collection() {
      startReadyClient();

      trackWithDetails(new TreeMap<>(Map.of("regions", Set.of("eu"))));

      assertThat(detailsSentOnClosing()).isEqualTo(json("{\"regions\":[\"eu\"]}"));
    }

    @Test
    void sends_a_float_inside_details_as_it_was_written() {
      startReadyClient();

      trackWithDetails(Map.of("ratio", 0.1f));

      assertThat(detailsSentOnClosing().get("ratio").getAsString()).isEqualTo("0.1");
    }

    @Test
    void sends_a_number_that_is_not_finite_inside_details_as_null() {
      startReadyClient();

      trackWithDetails(Map.of("ratio", Double.NaN));

      assertThat(detailsSentOnClosing()).isEqualTo(json("{\"ratio\":null}"));
      assertThat(warnings()).isEmpty();
    }

    @Test
    void leaves_out_details_holding_a_date_sends_the_event_and_logs_a_warning() {
      startReadyClient();

      trackWithDetails(Map.of("at", Instant.parse("2026-11-06T10:00:00Z")));

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_DETAILS));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void leaves_out_details_holding_an_array_sends_the_event_and_logs_a_warning() {
      startReadyClient();

      trackWithDetails(Map.of("items", new String[] {"book"}));

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_DETAILS));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void leaves_out_details_holding_a_number_type_whose_text_is_not_a_number() {
      startReadyClient();

      trackWithDetails(Map.of("count", new NumberWithText("many")));

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_DETAILS));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void leaves_out_details_holding_a_map_with_a_key_that_is_not_a_string() {
      startReadyClient();

      trackWithDetails(Map.of("nested", Map.of(1, "one")));

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_DETAILS));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void leaves_out_details_that_cannot_be_read_without_throwing() {
      startReadyClient();

      trackWithDetails(
          Map.of(
              "nested",
              new AbstractMap<String, Object>() {
                @Override
                public Set<Map.Entry<String, Object>> entrySet() {
                  throw new IllegalStateException("The cart is gone");
                }
              }));

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_DETAILS));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void leaves_out_details_that_hold_themselves_without_throwing() {
      startReadyClient();
      Map<String, Object> nested = new HashMap<>();
      nested.put("first", nested);
      nested.put("second", nested);

      trackWithDetails(Map.of("nested", nested));

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_DETAILS));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void leaves_out_details_nested_far_beyond_the_limit_without_throwing() {
      startReadyClient();
      List<Object> nested = new ArrayList<>();
      for (int level = 0; level < 100_000; level++) {
        List<Object> outer = new ArrayList<>();
        outer.add(nested);
        nested = outer;
      }

      trackWithDetails(Map.of("nested", nested));

      assertThat(firstTrackedEventWithoutItsTimeSentOnClosing())
          .isEqualTo(json(EVENT_WITHOUT_ITS_DETAILS));
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void sends_the_details_as_they_were_when_the_event_was_tracked() {
      startReadyClient();
      List<Object> items = new ArrayList<>(List.of("book"));

      trackWithDetails(Map.of("items", items));
      items.add("pen");

      assertThat(detailsSentOnClosing()).isEqualTo(json("{\"items\":[\"book\"]}"));
    }
  }

  @Nested
  @DisplayName("the limit of 1,000 events per telemetry batch")
  class BatchLimit {

    private void trackEvents(int count) {
      for (int number = 1; number <= count; number++) {
        client.track("event-" + number);
      }
    }

    @Test
    void sends_1000_events_tracked_before_a_batch_is_sent() {
      startReadyClient();

      trackEvents(1_000);

      assertThat(trackedEventsSentOnClosing()).hasSize(1_000);
      assertThat(warnings()).isEmpty();
    }

    @Test
    void does_not_send_the_1001st_event_tracked_before_a_batch_is_sent_and_logs_a_warning() {
      startReadyClient();

      trackEvents(1_001);

      List<String> names = trackedEventNamesSentOnClosing();
      assertThat(names).hasSize(1_000);
      assertThat(names.get(0)).isEqualTo("event-1");
      assertThat(names.get(999)).isEqualTo("event-1000");
      assertThat(warnings()).hasSize(1);
    }

    @Test
    void logs_one_warning_for_every_event_beyond_the_limit_of_one_batch() {
      startReadyClient();

      trackEvents(1_003);

      assertThat(warnings()).hasSize(1);
    }
  }

  private static final class NumberWithText extends Number {

    private static final long serialVersionUID = 1L;

    private final String text;

    NumberWithText(String text) {
      this.text = text;
    }

    @Override
    public int intValue() {
      return 0;
    }

    @Override
    public long longValue() {
      return 0;
    }

    @Override
    public float floatValue() {
      return 0;
    }

    @Override
    public double doubleValue() {
      return 0;
    }

    @Override
    public String toString() {
      return text;
    }
  }
}
