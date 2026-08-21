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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StreamingTransportTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
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
}
