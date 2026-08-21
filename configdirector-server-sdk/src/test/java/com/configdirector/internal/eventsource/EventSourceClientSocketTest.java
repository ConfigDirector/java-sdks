package com.configdirector.internal.eventsource;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.testing.TestHttpServer;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The whole client over real sockets, where the fake transport cannot show a thread parked. */
class EventSourceClientSocketTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final List<EventSourceMessage> messages = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger connects = new AtomicInteger();

  private EventSourceClient client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }


  // synchronizedList guards single operations, not iteration: the reader thread is still appending
  // while AssertJ walks the list, which surfaces as a ConcurrentModificationException. Every
  // assertion works from a copy taken under the list's own lock instead.
  private static <T> List<T> snapshot(List<T> shared) {
    synchronized (shared) {
      return new ArrayList<>(shared);
    }
  }

  private EventSourceClient.Builder clientBuilder(String url) {
    return EventSourceClient.builder(url)
        .onMessage(messages::add)
        .onConnect(connects::incrementAndGet)
        .calculateReconnectDelay(state -> Duration.ofMillis(20));
  }

  @Test
  void delivers_events_as_the_server_sends_them() throws Exception {
    BlockingQueue<TestHttpServer.Session> live = new LinkedBlockingQueue<>();
    try (TestHttpServer server =
        TestHttpServer.start(
            session -> {
              session.respondStreaming();
              live.add(session);
            })) {

      client = clientBuilder(server.url("/stream")).build();
      client.connect();

      TestHttpServer.Session session = live.poll(5, TimeUnit.SECONDS);
      assertThat(session).isNotNull();
      await().atMost(TIMEOUT).until(() -> client.readyState() == ReadyState.OPEN);

      session.send("data: one\n\n");
      await().atMost(TIMEOUT).until(() -> messages.size() == 1);

      session.send(": keepalive\n\ndata: two\n\n");
      await().atMost(TIMEOUT).until(() -> messages.size() == 2);

      assertThat(snapshot(messages)).extracting(EventSourceMessage::data).containsExactly("one", "two");
    }
  }

  @Test
  void close_returns_promptly_while_a_read_is_parked() throws Exception {
    BlockingQueue<TestHttpServer.Session> live = new LinkedBlockingQueue<>();
    try (TestHttpServer server =
        TestHttpServer.start(
            session -> {
              session.respondStreaming();
              live.add(session);
            })) {

      client = clientBuilder(server.url("/stream")).build();
      client.connect();
      assertThat(live.poll(5, TimeUnit.SECONDS)).isNotNull();
      await().atMost(TIMEOUT).until(() -> client.readyState() == ReadyState.OPEN);
      // Long enough that the reader is certainly parked in a socket read.
      Thread.sleep(300);

      long start = System.nanoTime();
      client.close();
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
      assertThat(client.readyState()).isEqualTo(ReadyState.CLOSED);
    }
  }

  @Test
  void does_not_reconnect_while_the_stream_is_merely_quiet() throws Exception {
    BlockingQueue<TestHttpServer.Session> live = new LinkedBlockingQueue<>();
    try (TestHttpServer server =
        TestHttpServer.start(
            session -> {
              session.respondStreaming();
              live.add(session);
            })) {

      client = clientBuilder(server.url("/stream")).build();
      client.connect();
      TestHttpServer.Session session = live.poll(5, TimeUnit.SECONDS);
      assertThat(session).isNotNull();
      await().atMost(TIMEOUT).until(() -> client.readyState() == ReadyState.OPEN);

      Thread.sleep(1_000);

      assertThat(server.connectionCount()).isEqualTo(1);
      assertThat(connects).hasValue(1);
      assertThat(client.readyState()).isEqualTo(ReadyState.OPEN);

      // Still live after the quiet spell.
      session.send("data: still here\n\n");
      await().atMost(TIMEOUT).until(() -> messages.size() == 1);
    }
  }

  @Test
  void reconnects_when_the_server_drops_the_stream() throws Exception {
    AtomicInteger served = new AtomicInteger();
    try (TestHttpServer server =
        TestHttpServer.start(
            session -> {
              session.respondStreaming();
              session.send("data: event " + served.incrementAndGet() + "\n\n");
              session.close();
            })) {

      client = clientBuilder(server.url("/stream")).build();
      client.connect();

      await().atMost(TIMEOUT).until(() -> messages.size() >= 3);
      assertThat(snapshot(messages)).extracting(EventSourceMessage::data).startsWith("event 1", "event 2");
    }
  }

  @Test
  void resumes_from_the_last_event_id_after_a_drop() throws Exception {
    List<String> sentIds = Collections.synchronizedList(new ArrayList<>());
    try (TestHttpServer server =
        TestHttpServer.start(
            session -> {
              sentIds.add(session.header("Last-Event-ID"));
              session.respondStreaming();
              session.send("id: 42\ndata: hello\n\n");
              session.close();
            })) {

      client = clientBuilder(server.url("/stream")).build();
      client.connect();

      await().atMost(TIMEOUT).until(() -> sentIds.size() >= 2);
      List<String> seen = snapshot(sentIds);
      assertThat(seen.get(0)).isNull();
      assertThat(seen.get(1)).isEqualTo("42");
    }
  }

  @Test
  void stops_without_retrying_on_no_content() throws Exception {
    try (TestHttpServer server =
        TestHttpServer.start(
            session -> {
              session.respond(204);
              session.close();
            })) {

      AtomicInteger disconnects = new AtomicInteger();
      client = clientBuilder(server.url("/stream")).onDisconnect(disconnects::incrementAndGet).build();
      client.connect();

      await().atMost(TIMEOUT).until(() -> disconnects.get() == 1);
      Thread.sleep(300);
      assertThat(server.connectionCount()).isEqualTo(1);
      assertThat(client.readyState()).isEqualTo(ReadyState.CLOSED);
    }
  }

  @Test
  void a_fatal_status_can_stop_the_client() throws Exception {
    try (TestHttpServer server =
        TestHttpServer.start(
            session -> {
              session.respond(401, "Content-Length: 0", "Connection: close");
              session.close();
            })) {

      List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
      client =
          clientBuilder(server.url("/stream"))
              .shouldReconnect(
                  state -> {
                    statuses.add(state.status());
                    return false;
                  })
              .build();
      client.connect();

      await().atMost(TIMEOUT).until(() -> client.readyState() == ReadyState.CLOSED);
      assertThat(snapshot(statuses)).containsExactly(401);
      assertThat(server.connectionCount()).isEqualTo(1);
    }
  }
}
