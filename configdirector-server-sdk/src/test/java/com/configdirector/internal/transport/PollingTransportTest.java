package com.configdirector.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.configdirector.internal.evaluation.Config;
import com.configdirector.testing.TestHttpServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PollingTransportTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
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

  private TransportOptions optionsFor(String baseUrl, Duration interval) {
    return new TransportOptions(
        "sdk-key",
        baseUrl,
        Map.of("sdkName", "java-server-sdk"),
        LoggerFactory.getLogger(PollingTransportTest.class),
        bundles::add,
        http,
        interval);
  }

  private static void respondWith(TestHttpServer.Session session, String body) {
    session.respond(
        200,
        "Content-Type: application/json",
        "Content-Length: " + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        "Connection: close");
    session.send(body);
    session.close();
  }

  private static TestHttpServer serverReturning(int status, String body) {
    return start(
        session -> {
          if (body == null) {
            session.respond(status, "Content-Length: 0", "Connection: close");
          } else {
            session.respond(
                status,
                "Content-Type: application/json",
                "Content-Length: " + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "Connection: close");
            session.send(body);
          }
          session.close();
        });
  }

  private static TestHttpServer start(Consumer<TestHttpServer.Session> handler) {
    try {
      return TestHttpServer.start(handler);
    } catch (java.io.IOException error) {
      throw new IllegalStateException(error);
    }
  }

  @Nested
  @DisplayName("the first fetch")
  class FirstFetch {

    @Test
    void delivers_the_bundle_it_receives() throws Exception {
      try (TestHttpServer server = serverReturning(200, BUNDLE)) {
        transport = new OneTimeTransport(optionsFor(server.url("/"), Duration.ZERO));

        transport.connect(TIMEOUT);

        assertThat(snapshot(bundles)).hasSize(1);
        Config config = snapshot(bundles).get(0).configs().get("k");
        assertThat(config.target().defaultValue()).isEqualTo("on");
      }
    }

    @Test
    void posts_the_sdk_key_and_meta_context() throws Exception {
      List<String> bodies = Collections.synchronizedList(new ArrayList<>());
      try (TestHttpServer server =
          start(
              session -> {
                bodies.add(session.bodyAsString());
                session.respond(200, "Content-Length: " + BUNDLE.length(), "Connection: close");
                session.send(BUNDLE);
                session.close();
              })) {
        transport = new OneTimeTransport(optionsFor(server.url("/"), Duration.ZERO));

        transport.connect(TIMEOUT);

        assertThat(snapshot(bodies).get(0))
            .contains("\"serverSdkKey\":\"sdk-key\"")
            .contains("\"sdkName\":\"java-server-sdk\"")
            .doesNotContain("lastUpdateTimestamp");
      }
    }

    @Test
    void sends_the_sdk_user_agent() throws Exception {
      List<String> agents = Collections.synchronizedList(new ArrayList<>());
      try (TestHttpServer server =
          start(
              session -> {
                agents.add(session.header("User-Agent"));
                session.respond(200, "Content-Length: " + BUNDLE.length(), "Connection: close");
                session.send(BUNDLE);
                session.close();
              })) {
        transport = new OneTimeTransport(optionsFor(server.url("/"), Duration.ZERO));

        transport.connect(TIMEOUT);

        assertThat(snapshot(agents).get(0)).startsWith("java-server-sdk/");
      }
    }

    @Test
    void a_no_content_response_delivers_nothing() throws Exception {
      try (TestHttpServer server = serverReturning(204, null)) {
        transport = new OneTimeTransport(optionsFor(server.url("/"), Duration.ZERO));

        transport.connect(TIMEOUT);

        assertThat(snapshot(bundles)).isEmpty();
      }
    }
  }

  @Nested
  @DisplayName("failures")
  class Failures {

    @Test
    void a_4xx_is_unrecoverable_and_stops_the_transport() throws Exception {
      try (TestHttpServer server = serverReturning(401, null)) {
        transport = new PollingTransport(optionsFor(server.url("/"), Duration.ofMillis(50)));

        assertThatExceptionOfType(ConfigDirectorConnectionException.class)
            .isThrownBy(() -> transport.connect(TIMEOUT))
            .withMessageContaining("401")
            .withMessageContaining("retry attempts will be ignored");

        // Nothing keeps polling after an unrecoverable failure.
        Thread.sleep(300);
        assertThat(server.connectionCount()).isEqualTo(1);
        assertThat(transport.isConnected()).isFalse();
      }
    }

    @Test
    void a_5xx_is_transient_and_polling_carries_on() throws Exception {
      try (TestHttpServer server = serverReturning(503, null)) {
        transport = new PollingTransport(optionsFor(server.url("/"), Duration.ofMillis(50)));

        assertThatExceptionOfType(ConfigDirectorConnectionException.class)
            .isThrownBy(() -> transport.connect(TIMEOUT))
            .withMessageContaining("503");

        // The first fetch threw, but the transport kept polling in the background.
        await().atMost(TIMEOUT).until(() -> server.connectionCount() > 1);
        assertThat(transport.isConnected()).isTrue();
      }
    }

    @Test
    void an_unparseable_body_is_reported_as_a_connection_failure() throws Exception {
      try (TestHttpServer server = serverReturning(200, "not json")) {
        transport = new OneTimeTransport(optionsFor(server.url("/"), Duration.ZERO));

        assertThatExceptionOfType(ConfigDirectorConnectionException.class)
            .isThrownBy(() -> transport.connect(TIMEOUT))
            .withMessageContaining("Failed to parse");
      }
    }

    @Test
    void an_unusable_url_is_unrecoverable() {
      transport = new OneTimeTransport(optionsFor("not-a-url", Duration.ZERO));

      assertThatExceptionOfType(ConfigDirectorConnectionException.class)
          .isThrownBy(() -> transport.connect(TIMEOUT))
          .withMessageContaining("retry attempts will be ignored");
    }

    @Test
    void a_refused_connection_is_transient() {
      transport = new OneTimeTransport(optionsFor("http://127.0.0.1:1/", Duration.ZERO));

      assertThatExceptionOfType(ConfigDirectorConnectionException.class)
          .isThrownBy(() -> transport.connect(TIMEOUT))
          .withMessageContaining("Connection failed with error");
    }
  }

  @Nested
  @DisplayName("polling")
  class Polling {

    @Test
    void keeps_fetching_on_the_interval() throws Exception {
      try (TestHttpServer server = serverReturning(200, BUNDLE)) {
        transport = new PollingTransport(optionsFor(server.url("/"), Duration.ofMillis(50)));

        transport.connect(TIMEOUT);

        await().atMost(TIMEOUT).until(() -> snapshot(bundles).size() >= 3);
        assertThat(transport.isConnected()).isTrue();
      }
    }

    @Test
    void echoes_the_last_timestamp_so_the_server_can_answer_with_a_delta() throws Exception {
      List<String> bodies = Collections.synchronizedList(new ArrayList<>());
      AtomicInteger served = new AtomicInteger();
      try (TestHttpServer server =
          start(
              session -> {
                bodies.add(session.bodyAsString());
                String body = BUNDLE.replace("\"t1\"", "\"t" + served.incrementAndGet() + "\"");
                session.respond(200, "Content-Length: " + body.length(), "Connection: close");
                session.send(body);
                session.close();
              })) {
        transport = new PollingTransport(optionsFor(server.url("/"), Duration.ofMillis(50)));

        transport.connect(TIMEOUT);

        await().atMost(TIMEOUT).until(() -> snapshot(bodies).size() >= 2);
        assertThat(snapshot(bodies).get(0)).doesNotContain("lastUpdateTimestamp");
        assertThat(snapshot(bodies).get(1)).contains("\"lastUpdateTimestamp\":\"t1\"");
      }
    }

    @Test
    void a_one_time_transport_never_starts_a_poller() throws Exception {
      try (TestHttpServer server = serverReturning(200, BUNDLE)) {
        transport = new OneTimeTransport(optionsFor(server.url("/"), Duration.ofSeconds(60)));

        transport.connect(TIMEOUT);

        Thread.sleep(200);
        assertThat(server.connectionCount()).isEqualTo(1);
        assertThat(transport.isConnected()).isFalse();
      }
    }

    @Test
    void close_stops_the_poller() throws Exception {
      try (TestHttpServer server = serverReturning(200, BUNDLE)) {
        transport = new PollingTransport(optionsFor(server.url("/"), Duration.ofMillis(50)));
        transport.connect(TIMEOUT);
        await().atMost(TIMEOUT).until(() -> snapshot(bundles).size() >= 2);

        transport.close();
        int afterClose = server.connectionCount();

        Thread.sleep(300);
        assertThat(server.connectionCount()).isEqualTo(afterClose);
        assertThat(transport.isConnected()).isFalse();
      }
    }

    @Test
    void close_during_the_first_fetch_leaves_no_poller_behind() throws Exception {
      try (TestHttpServer.Queued server = TestHttpServer.startQueued()) {
        transport = new PollingTransport(optionsFor(server.url("/"), Duration.ofMillis(50)));

        Thread connecting = new Thread(() -> transport.connect(TIMEOUT), "connecting");
        connecting.start();

        // next() returns once the request is fully on the wire and nothing has answered it, so
        // close() lands in the window between the first fetch and the poller it starts after.
        TestHttpServer.Session session = server.next();
        transport.close();
        respondWith(session, BUNDLE);
        connecting.join(TIMEOUT.toMillis());

        int afterClose = server.connectionCount();
        Thread.sleep(300);
        assertThat(transport.isConnected()).isFalse();
        assertThat(server.connectionCount()).isEqualTo(afterClose);
      }
    }

    @Test
    void close_is_idempotent() throws Exception {
      try (TestHttpServer server = serverReturning(200, BUNDLE)) {
        transport = new PollingTransport(optionsFor(server.url("/"), Duration.ofMillis(50)));
        transport.connect(TIMEOUT);

        transport.close();
        transport.close();

        assertThat(transport.isConnected()).isFalse();
      }
    }
  }

  @Nested
  @DisplayName("the factory")
  class Factory {

    @Test
    void builds_the_transport_the_mode_names() {
      TransportOptions options = optionsFor("https://api.test/", Duration.ofSeconds(60));

      assertThat(Transports.create(ConnectionMode.STREAMING, options))
          .isInstanceOf(StreamingTransport.class);
      assertThat(Transports.create(ConnectionMode.POLLING, options))
          .isInstanceOf(PollingTransport.class);
      assertThat(Transports.create(ConnectionMode.ONE_TIME, options))
          .isInstanceOf(OneTimeTransport.class);
    }
  }
}
