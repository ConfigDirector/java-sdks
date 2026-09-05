package com.configdirector.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.configdirector.testing.TestHttpServer;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StreamingTransportTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  // What the server promises: axum's KeepAlive::default() sends a comment every 15 seconds, so a
  // stream that has been silent for meaningfully longer than that is not idle, it is broken.
  private static final Duration SERVER_KEEPALIVE = Duration.ofSeconds(15);
  private static final String BUNDLE =
      "{\"timestamp\":\"t1\",\"configs\":{\"k\":{\"id\":\"c1\",\"key\":\"k\",\"type\":\"string\","
          + "\"target\":{\"defaultValue\":\"on\",\"rules\":[]}}}}";

  private final List<ConfigBundle> bundles = Collections.synchronizedList(new ArrayList<>());
  private final HttpClient http = new HttpClient();

  private Transport transport;

  @AfterEach
  void tearDown() {
    if (transport != null) {
      transport.close();
    }
    http.close();
  }

  private static <T> List<T> snapshot(List<T> shared) {
    synchronized (shared) {
      return new ArrayList<>(shared);
    }
  }

  private TransportOptions optionsFor(String baseUrl) {
    return new TransportOptions(
        "sdk-key",
        baseUrl,
        Map.of("sdkName", "java-server-sdk"),
        LoggerFactory.getLogger(StreamingTransportTest.class),
        bundles::add,
        http,
        Duration.ofSeconds(60));
  }

  private static TestHttpServer start(Consumer<TestHttpServer.Session> handler) {
    try {
      return TestHttpServer.start(handler);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String sessionIdOf(String body) {
    Matcher matcher = Pattern.compile("\"sessionId\":\"([^\"]*)\"").matcher(body);
    assertThat(matcher.find()).as("body carries a sessionId: %s", body).isTrue();
    return matcher.group(1);
  }

  @Nested
  @DisplayName("a live stream")
  class Live {

    @Test
    void connect_returns_once_the_stream_opens() throws Exception {
      BlockingQueue<TestHttpServer.Session> live = new LinkedBlockingQueue<>();
      try (TestHttpServer server =
          start(
              session -> {
                session.respondStreaming();
                live.add(session);
              })) {
        transport = new StreamingTransport(optionsFor(server.url("/")));

        transport.connect(TIMEOUT);

        assertThat(transport.isConnected()).isTrue();
        assertThat(live.poll(5, TimeUnit.SECONDS)).isNotNull();
      }
    }

    @Test
    void delivers_a_bundle_pushed_as_an_event() throws Exception {
      BlockingQueue<TestHttpServer.Session> live = new LinkedBlockingQueue<>();
      try (TestHttpServer server =
          start(
              session -> {
                session.respondStreaming();
                live.add(session);
              })) {
        transport = new StreamingTransport(optionsFor(server.url("/")));
        transport.connect(TIMEOUT);

        TestHttpServer.Session session = live.poll(5, TimeUnit.SECONDS);
        assertThat(session).isNotNull();
        session.send("data: " + BUNDLE + "\n\n");

        await().atMost(TIMEOUT).until(() -> !snapshot(bundles).isEmpty());
        assertThat(snapshot(bundles).get(0).configs()).containsKey("k");
      }
    }

    @Test
    void an_unparseable_event_is_skipped_without_dropping_the_stream() throws Exception {
      BlockingQueue<TestHttpServer.Session> live = new LinkedBlockingQueue<>();
      try (TestHttpServer server =
          start(
              session -> {
                session.respondStreaming();
                live.add(session);
              })) {
        transport = new StreamingTransport(optionsFor(server.url("/")));
        transport.connect(TIMEOUT);

        TestHttpServer.Session session = live.poll(5, TimeUnit.SECONDS);
        assertThat(session).isNotNull();
        session.send("data: not json\n\n");
        session.send("data: " + BUNDLE + "\n\n");

        await().atMost(TIMEOUT).until(() -> !snapshot(bundles).isEmpty());
        assertThat(snapshot(bundles)).hasSize(1);
        assertThat(transport.isConnected()).isTrue();
      }
    }

    @Test
    void posts_the_sdk_key_and_accepts_an_event_stream() throws Exception {
      BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
      BlockingQueue<String> accepts = new LinkedBlockingQueue<>();
      try (TestHttpServer server =
          start(
              session -> {
                bodies.add(session.bodyAsString());
                accepts.add(session.header("Accept"));
                session.respondStreaming();
              })) {
        transport = new StreamingTransport(optionsFor(server.url("/")));
        transport.connect(TIMEOUT);

        assertThat(bodies.poll(5, TimeUnit.SECONDS)).contains("\"serverSdkKey\":\"sdk-key\"");
        assertThat(accepts.poll(5, TimeUnit.SECONDS)).isEqualTo("text/event-stream");
      }
    }

    @Test
    void sends_a_uuid_session_id() throws Exception {
      BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
      try (TestHttpServer server =
          start(
              session -> {
                bodies.add(session.bodyAsString());
                session.respondStreaming();
              })) {
        transport = new StreamingTransport(optionsFor(server.url("/")));
        transport.connect(TIMEOUT);

        String body = bodies.poll(5, TimeUnit.SECONDS);
        assertThat(body).isNotNull();
        String sessionId = sessionIdOf(body);
        assertThat(UUID.fromString(sessionId).toString()).isEqualTo(sessionId);
      }
    }
  }

  @Nested
  @DisplayName("failures")
  class Failures {

    @Test
    void a_4xx_is_unrecoverable_and_surfaces_from_connect() throws Exception {
      try (TestHttpServer server =
          start(
              session -> {
                session.respond(401, "Content-Length: 0", "Connection: close");
                session.close();
              })) {
        transport = new StreamingTransport(optionsFor(server.url("/")));

        assertThatExceptionOfType(ConfigDirectorConnectionException.class)
            .isThrownBy(() -> transport.connect(TIMEOUT))
            .withMessageContaining("401")
            .withMessageContaining("retry attempts will be ignored");

        assertThat(transport.isConnected()).isFalse();
        // Nothing retries after an unrecoverable status.
        Thread.sleep(300);
        assertThat(server.connectionCount()).isEqualTo(1);
      }
    }

    @Test
    void a_5xx_keeps_retrying_and_connect_returns_on_the_timeout() throws Exception {
      try (TestHttpServer server =
          start(
              session -> {
                session.respond(503, "Content-Length: 0", "Connection: close");
                session.close();
              })) {
        transport = new StreamingTransport(optionsFor(server.url("/")));

        // Not a failure: the stream keeps retrying and the caller is told it is not connected.
        transport.connect(Duration.ofMillis(300));

        assertThat(transport.isConnected()).isFalse();
        await().atMost(TIMEOUT).until(() -> server.connectionCount() >= 2);
      }
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    @Test
    void close_drops_the_stream() throws Exception {
      try (TestHttpServer server = start(TestHttpServer.Session::respondStreaming)) {
        transport = new StreamingTransport(optionsFor(server.url("/")));
        transport.connect(TIMEOUT);
        assertThat(transport.isConnected()).isTrue();

        transport.close();

        assertThat(transport.isConnected()).isFalse();
      }
    }

    @Test
    void connect_after_close_opens_a_fresh_stream() throws Exception {
      try (TestHttpServer server = start(TestHttpServer.Session::respondStreaming)) {
        transport = new StreamingTransport(optionsFor(server.url("/")));
        transport.connect(TIMEOUT);
        transport.close();

        transport.connect(TIMEOUT);

        assertThat(transport.isConnected()).isTrue();
        assertThat(server.connectionCount()).isEqualTo(2);
      }
    }

    @Test
    void close_is_idempotent() throws Exception {
      try (TestHttpServer server = start(TestHttpServer.Session::respondStreaming)) {
        transport = new StreamingTransport(optionsFor(server.url("/")));
        transport.connect(TIMEOUT);

        transport.close();
        transport.close();

        assertThat(transport.isConnected()).isFalse();
      }
    }

    @Test
    void close_before_connect_is_harmless() {
      transport = new StreamingTransport(optionsFor("https://example.test/"));

      transport.close();

      assertThat(transport.isConnected()).isFalse();
    }
  }

  @Nested
  @DisplayName("a stream that goes silent")
  class Stalled {

    @Test
    void is_given_up_on_and_reconnected() throws Exception {
      // The stream opens, delivers once, and then says nothing at all: no events, and no keepalive
      // comments either. Nothing closes the socket, so only a read timeout can notice.
      try (TestHttpServer server =
          start(
              session -> {
                session.respondStreaming();
                session.send("data: " + BUNDLE + "\n\n");
              })) {
        transport = new StreamingTransport(optionsFor(server.url("/")), Duration.ofMillis(300));

        transport.connect(TIMEOUT);
        await().atMost(TIMEOUT).until(() -> !snapshot(bundles).isEmpty());

        await()
            .atMost(TIMEOUT)
            .untilAsserted(() -> assertThat(server.connectionCount()).isGreaterThan(1));
      }
    }

    @Test
    void is_reconnected_with_a_fresh_session_id() throws Exception {
      BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
      try (TestHttpServer server =
          start(
              session -> {
                bodies.add(session.bodyAsString());
                session.respondStreaming();
                session.send("data: " + BUNDLE + "\n\n");
              })) {
        transport = new StreamingTransport(optionsFor(server.url("/")), Duration.ofMillis(300));
        transport.connect(TIMEOUT);

        String first = bodies.poll(5, TimeUnit.SECONDS);
        String second = bodies.poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(sessionIdOf(second)).isNotEqualTo(sessionIdOf(first));
      }
    }

    @Test
    void is_allowed_to_miss_a_keepalive_or_two_before_that() {
      Duration readTimeout =
          new StreamingTransport(optionsFor("https://api.test/")).readTimeout();

      // Zero would mean waiting forever, which is how a half-open connection goes unnoticed.
      assertThat(readTimeout).isPositive();
      assertThat(readTimeout).isGreaterThan(SERVER_KEEPALIVE.multipliedBy(2));
    }
  }

  @Nested
  @DisplayName("reconnect backoff")
  class Backoff {

    private static final int SAMPLES = 500;

    // EventSourceClient replaces a delay outside this range with the server's own, which would
    // quietly undo the backoff.
    private static final Duration MIN_ACCEPTED = Duration.ofMillis(1);
    private static final Duration MAX_ACCEPTED = Duration.ofHours(1);

    private static List<Duration> sample(int attempt) {
      List<Duration> delays = new ArrayList<>(SAMPLES);
      for (int i = 0; i < SAMPLES; i++) {
        delays.add(StreamingTransport.backoffDelay(attempt));
      }
      return delays;
    }

    @Test
    void a_delay_falls_between_half_the_ceiling_and_the_ceiling() {
      for (int attempt = 1; attempt <= 9; attempt++) {
        Duration ceiling = Duration.ofSeconds(1L << attempt);
        assertThat(sample(attempt))
            .allSatisfy(
                delay ->
                    assertThat(delay)
                        .isGreaterThanOrEqualTo(ceiling.dividedBy(2))
                        .isLessThan(ceiling));
      }
    }

    @Test
    void the_ceiling_stops_growing_past_the_cap() {
      Duration ceiling = Duration.ofSeconds(512);
      assertThat(sample(50))
          .allSatisfy(
              delay ->
                  assertThat(delay).isGreaterThanOrEqualTo(ceiling.dividedBy(2)).isLessThan(ceiling));
    }

    // The point of the jitter: a fleet that lost the stream together must not come back together.
    // Sampling cannot prove dispersion, so this asserts the two things lockstep would break,
    // that the delays take many different values, and that they spread across the whole window.
    @Test
    void delays_spread_across_the_window_rather_than_landing_together() {
      for (int attempt : new int[] {1, 9}) {
        List<Duration> delays = sample(attempt);
        Duration ceiling = Duration.ofSeconds(1L << attempt);
        Duration window = ceiling.dividedBy(2);

        assertThat(Set.copyOf(delays)).hasSizeGreaterThan(SAMPLES / 4);
        Duration lowest = delays.stream().min(Duration::compareTo).orElseThrow();
        Duration highest = delays.stream().max(Duration::compareTo).orElseThrow();
        assertThat(highest.minus(lowest)).isGreaterThan(window.dividedBy(2));
      }
    }

    @Test
    void every_delay_stays_within_the_range_the_event_source_accepts() {
      for (int attempt : new int[] {1, 5, 9, 50}) {
        assertThat(sample(attempt))
            .allSatisfy(
                delay ->
                    assertThat(delay).isBetween(MIN_ACCEPTED, MAX_ACCEPTED));
      }
    }
  }
}
