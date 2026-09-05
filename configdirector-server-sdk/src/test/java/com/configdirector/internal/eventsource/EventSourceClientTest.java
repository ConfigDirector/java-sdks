package com.configdirector.internal.eventsource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Drives the connection loop through the StreamOpener seam, so failures are scripted. */
class EventSourceClientTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final FakeStreamOpener opener = new FakeStreamOpener();
  private final List<EventSourceMessage> messages = Collections.synchronizedList(new ArrayList<>());
  private final List<String> comments = Collections.synchronizedList(new ArrayList<>());
  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger connects = new AtomicInteger();
  private final AtomicInteger disconnects = new AtomicInteger();

  private EventSourceClient client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  private EventSourceClient.Builder clientBuilder() {
    return EventSourceClient.builder("https://example.test/stream")
        .opener(opener)
        .onMessage(messages::add)
        .onComment(comments::add)
        .onError(errors::add)
        .onConnect(connects::incrementAndGet)
        .onDisconnect(disconnects::incrementAndGet)
        // Keeps a reconnecting test from spending real seconds between attempts.
        .calculateReconnectDelay(state -> Duration.ofMillis(1));
  }

  private EventSourceClient connected(EventSourceClient.Builder builder) {
    client = builder.build();
    client.connect();
    return client;
  }


  // synchronizedList guards single operations, not iteration: the reader thread is still appending
  // while AssertJ walks the list, which surfaces as a ConcurrentModificationException. Every
  // assertion works from a copy taken under the list's own lock instead.
  private static <T> List<T> snapshot(List<T> shared) {
    synchronized (shared) {
      return new ArrayList<>(shared);
    }
  }

  private static void awaitUntil(Callable<Boolean> condition) {
    await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(2)).until(condition);
  }

  @Nested
  @DisplayName("request configuration")
  class RequestConfiguration {

    @Test
    void sends_the_event_stream_accept_header() {
      connected(clientBuilder());

      awaitUntil(() -> opener.attemptCount() > 0);
      assertThat(opener.lastRequest().headers()).containsEntry("Accept", "text/event-stream");
    }

    @Test
    void merges_caller_headers() {
      connected(clientBuilder().headers(Map.of("Authorization", "Bearer token")));

      awaitUntil(() -> opener.attemptCount() > 0);
      assertThat(opener.lastRequest().headers())
          .containsEntry("Accept", "text/event-stream")
          .containsEntry("Authorization", "Bearer token");
    }

    @Test
    void a_caller_header_may_override_accept() {
      connected(clientBuilder().headers(Map.of("Accept", "application/json")));

      awaitUntil(() -> opener.attemptCount() > 0);
      assertThat(opener.lastRequest().headers()).containsEntry("Accept", "application/json");
    }

    @Test
    void sends_a_configured_last_event_id() {
      connected(clientBuilder().lastEventId("42"));

      awaitUntil(() -> opener.attemptCount() > 0);
      assertThat(opener.lastRequest().headers()).containsEntry("Last-Event-ID", "42");
    }

    @Test
    void omits_the_header_when_there_is_no_last_event_id() {
      connected(clientBuilder());

      awaitUntil(() -> opener.attemptCount() > 0);
      assertThat(opener.lastRequest().headers()).doesNotContainKey("Last-Event-ID");
    }

    @Test
    void sends_a_server_supplied_event_id_on_reconnect() {
      opener.script(() -> FakeResponseStream.of(200, "id: 99\ndata: hello\n\n"));

      connected(clientBuilder());

      awaitUntil(() -> opener.attemptCount() >= 2);
      assertThat(opener.lastRequest().headers()).containsEntry("Last-Event-ID", "99");
    }

    @Test
    void passes_the_method_and_body_through() {
      byte[] body = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);

      connected(clientBuilder().method("POST").body(body));

      awaitUntil(() -> opener.attemptCount() > 0);
      StreamRequest request = opener.lastRequest();
      assertThat(request.method()).isEqualTo("POST");
      assertThat(request.body()).isEqualTo(body);
    }

    @Test
    void invokes_a_body_supplier_on_each_attempt() {
      AtomicInteger calls = new AtomicInteger();

      connected(
          clientBuilder()
              .method("POST")
              .body(() -> ("attempt-" + calls.incrementAndGet()).getBytes(StandardCharsets.UTF_8)));

      awaitUntil(() -> opener.attemptCount() >= 2);
      List<StreamRequest> requests = opener.requests();
      assertThat(new String(requests.get(0).body(), StandardCharsets.UTF_8)).isEqualTo("attempt-1");
      assertThat(new String(requests.get(1).body(), StandardCharsets.UTF_8)).isEqualTo("attempt-2");
    }

    @Test
    void passes_the_timeouts_and_redirect_policy_through() {
      connected(
          clientBuilder()
              .connectTimeout(Duration.ofSeconds(3))
              .readTimeout(Duration.ofSeconds(30))
              .followRedirects(false));

      awaitUntil(() -> opener.attemptCount() > 0);
      StreamRequest request = opener.lastRequest();
      assertThat(request.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
      assertThat(request.readTimeout()).isEqualTo(Duration.ofSeconds(30));
      assertThat(request.followRedirects()).isFalse();
    }
  }

  @Nested
  @DisplayName("handlers")
  class Handlers {

    @Test
    void calls_on_connect_when_the_stream_opens() {
      opener.thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());

      awaitUntil(() -> connects.get() == 1);
    }

    @Test
    void does_not_call_on_connect_for_an_error_response() {
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(clientBuilder());

      awaitUntil(() -> opener.attemptCount() >= 3);
      assertThat(connects).hasValue(0);
    }

    @Test
    void delivers_messages_in_order() {
      opener.thereafter(
          () -> FakeResponseStream.live(200)); // stays open so nothing reconnects mid-assert
      FakeResponseStream stream = FakeResponseStream.live(200);
      opener.script(() -> stream);

      connected(clientBuilder());

      awaitUntil(() -> connects.get() == 1);
      stream.push("data: one\n\ndata: two\n\ndata: three\n\n");

      awaitUntil(() -> messages.size() == 3);
      assertThat(snapshot(messages))
          .extracting(EventSourceMessage::data)
          .containsExactly("one", "two", "three");
    }

    @Test
    void reports_comments() {
      FakeResponseStream stream = FakeResponseStream.live(200);
      opener.script(() -> stream).thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());
      awaitUntil(() -> connects.get() == 1);
      stream.push(": keepalive\n");

      awaitUntil(() -> comments.size() == 1);
      assertThat(snapshot(comments)).containsExactly("keepalive");
    }

    @Test
    void calls_on_disconnect_for_204() {
      opener.thereafter(() -> FakeResponseStream.of(204, ""));

      connected(clientBuilder());

      awaitUntil(() -> disconnects.get() == 1);
      // 204 is the server saying "do not come back", so nothing retries.
      assertThat(opener.attemptCount()).isEqualTo(1);
    }

    @Test
    void calls_on_disconnect_when_reconnecting_is_declined() {
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(clientBuilder().shouldReconnect(state -> false));

      awaitUntil(() -> disconnects.get() == 1);
      assertThat(client.readyState()).isEqualTo(ReadyState.CLOSED);
    }

    @Test
    void does_not_call_on_disconnect_for_an_explicit_close() {
      opener.thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());
      awaitUntil(() -> connects.get() == 1);
      client.close();

      assertThat(disconnects).hasValue(0);
    }
  }

  @Nested
  @DisplayName("ready state")
  class ReadyStateTransitions {

    @Test
    void starts_closed() {
      client = clientBuilder().build();

      assertThat(client.readyState()).isEqualTo(ReadyState.CLOSED);
    }

    @Test
    void is_connecting_as_soon_as_connect_returns() {
      opener.thereafter(() -> FakeResponseStream.live(200));
      client = clientBuilder().build();

      client.connect();

      // Either still CONNECTING or already OPEN, but never CLOSED.
      assertThat(client.readyState()).isNotEqualTo(ReadyState.CLOSED);
    }

    @Test
    void is_open_while_the_stream_is_live() {
      opener.thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());

      awaitUntil(() -> client.readyState() == ReadyState.OPEN);
    }

    @Test
    void returns_to_closed_after_close() {
      opener.thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());
      awaitUntil(() -> client.readyState() == ReadyState.OPEN);
      client.close();

      assertThat(client.readyState()).isEqualTo(ReadyState.CLOSED);
    }

    @Test
    void returns_to_closed_when_the_server_says_no_content() {
      opener.thereafter(() -> FakeResponseStream.of(204, ""));

      connected(clientBuilder());

      awaitUntil(() -> client.readyState() == ReadyState.CLOSED);
    }
  }

  @Nested
  @DisplayName("errors")
  class Errors {

    @Test
    void reports_a_transport_failure() {
      StreamConnectException failure =
          new StreamConnectException("refused", new java.io.IOException("refused"));
      opener.script(FakeStreamOpener.failing(failure)).thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());

      awaitUntil(() -> !errors.isEmpty());
      assertThat(snapshot(errors)).first().isSameAs(failure);
    }

    @Test
    void does_not_report_http_error_statuses_as_errors() {
      opener.thereafter(() -> FakeResponseStream.of(503, ""));

      connected(clientBuilder());

      awaitUntil(() -> opener.attemptCount() >= 3);
      assertThat(snapshot(errors)).isEmpty();
    }

    @Test
    void reports_an_out_of_range_reconnect_delay() {
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(clientBuilder().calculateReconnectDelay(state -> Duration.ofHours(2)));

      awaitUntil(() -> !errors.isEmpty());
      assertThat(snapshot(errors)).first().isInstanceOf(ValueOutOfRangeException.class);
    }

    @Test
    void reports_a_null_reconnect_delay() {
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(clientBuilder().calculateReconnectDelay(state -> null));

      awaitUntil(() -> !errors.isEmpty());
      assertThat(snapshot(errors)).first().isInstanceOf(ValueOutOfRangeException.class);
    }

    @Test
    void accepts_the_boundary_delays() {
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(clientBuilder().calculateReconnectDelay(state -> Duration.ofMillis(1)));

      awaitUntil(() -> opener.attemptCount() >= 3);
      assertThat(snapshot(errors)).isEmpty();
    }

    @Test
    void a_throwing_message_handler_does_not_stop_the_loop() {
      FakeResponseStream stream = FakeResponseStream.live(200);
      opener.script(() -> stream).thereafter(() -> FakeResponseStream.live(200));

      connected(
          clientBuilder()
              .onMessage(
                  message -> {
                    messages.add(message);
                    throw new IllegalStateException("handler blew up");
                  }));

      awaitUntil(() -> connects.get() == 1);
      stream.push("data: one\n\ndata: two\n\n");

      awaitUntil(() -> messages.size() == 2);
      assertThat(client.readyState()).isEqualTo(ReadyState.OPEN);
    }

    @Test
    void a_throwing_should_reconnect_falls_back_to_reconnecting() {
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(
          clientBuilder()
              .shouldReconnect(
                  state -> {
                    throw new IllegalStateException("handler blew up");
                  }));

      awaitUntil(() -> opener.attemptCount() >= 3);
      assertThat(disconnects).hasValue(0);
    }

    @Test
    void a_throwing_delay_handler_falls_back_to_the_server_delay() {
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(
          clientBuilder()
              .calculateReconnectDelay(
                  state -> {
                    throw new IllegalStateException("handler blew up");
                  }));

      // The fallback is the two-second server default, so one retry is all this can observe
      // without waiting on it.
      awaitUntil(() -> opener.attemptCount() >= 1);
      assertThat(client.readyState()).isEqualTo(ReadyState.CONNECTING);
    }

    @Test
    void a_parser_limit_ends_the_stream_and_reconnects() {
      FakeResponseStream stream = FakeResponseStream.live(200);
      opener.script(() -> stream).thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder().maxLineChars(32));
      awaitUntil(() -> connects.get() == 1);
      stream.push("data: " + "a".repeat(200));

      awaitUntil(() -> !errors.isEmpty());
      assertThat(snapshot(errors)).first().isInstanceOf(StreamTooLargeException.class);
      awaitUntil(() -> opener.attemptCount() >= 2);
    }
  }

  @Nested
  @DisplayName("status handling")
  class StatusHandling {

    @Test
    void passes_the_status_to_should_reconnect() {
      List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
      opener.thereafter(() -> FakeResponseStream.of(503, ""));

      connected(
          clientBuilder()
              .shouldReconnect(
                  state -> {
                    seen.add(state.status());
                    return seen.size() < 2;
                  }));

      awaitUntil(() -> disconnects.get() == 1);
      assertThat(snapshot(seen)).containsExactly(503, 503);
    }

    @Test
    void a_stream_that_ends_carries_no_status_error() {
      List<ReconnectionState> seen = Collections.synchronizedList(new ArrayList<>());
      opener.script(() -> FakeResponseStream.of(200, "data: hello\n\n"));

      connected(
          clientBuilder()
              .shouldReconnect(
                  state -> {
                    seen.add(state);
                    return false;
                  }));

      awaitUntil(() -> !seen.isEmpty());
      ReconnectionState state = snapshot(seen).get(0);
      assertThat(state.status()).isEqualTo(200);
      assertThat(state.error()).isInstanceOf(StreamClosedException.class);
    }

    @Test
    void reconnects_after_a_server_error_by_default() {
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(clientBuilder());

      awaitUntil(() -> opener.attemptCount() >= 3);
    }
  }

  @Nested
  @DisplayName("reconnection")
  class Reconnection {

    @Test
    void reconnects_when_the_stream_ends() {
      opener.thereafter(() -> FakeResponseStream.of(200, "data: hello\n\n"));

      connected(clientBuilder());

      awaitUntil(() -> messages.size() >= 3);
    }

    @Test
    void counts_consecutive_failures() {
      List<Integer> attempts = Collections.synchronizedList(new ArrayList<>());
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(
          clientBuilder()
              .calculateReconnectDelay(
                  state -> {
                    attempts.add(state.attempt());
                    return Duration.ofMillis(1);
                  }));

      awaitUntil(() -> attempts.size() >= 3);
      assertThat(snapshot(attempts).subList(0, 3)).containsExactly(1, 2, 3);
    }

    @Test
    void a_successful_connection_resets_the_counter() {
      List<Integer> attempts = Collections.synchronizedList(new ArrayList<>());
      // Fail, fail, then a stream that opens and ends -- which resets the count before failing.
      opener
          .script(() -> FakeResponseStream.of(500, ""))
          .script(() -> FakeResponseStream.of(500, ""))
          .script(() -> FakeResponseStream.of(200, "data: hello\n\n"))
          .thereafter(() -> FakeResponseStream.of(500, ""));

      connected(
          clientBuilder()
              .calculateReconnectDelay(
                  state -> {
                    attempts.add(state.attempt());
                    return Duration.ofMillis(1);
                  }));

      awaitUntil(() -> attempts.size() >= 4);
      assertThat(snapshot(attempts).subList(0, 4)).containsExactly(1, 2, 1, 2);
    }

    @Test
    void a_fresh_connect_resets_the_counter() {
      List<Integer> attempts = Collections.synchronizedList(new ArrayList<>());
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(
          clientBuilder()
              .calculateReconnectDelay(
                  state -> {
                    attempts.add(state.attempt());
                    return Duration.ofMillis(1);
                  }));
      awaitUntil(() -> attempts.size() >= 3);
      client.close();
      attempts.clear();
      client.connect();

      awaitUntil(() -> !attempts.isEmpty());
      assertThat(snapshot(attempts).get(0)).isEqualTo(1);
    }

    @Test
    void the_retry_field_sets_the_server_delay() {
      List<Duration> delays = Collections.synchronizedList(new ArrayList<>());
      opener.thereafter(() -> FakeResponseStream.of(200, "retry: 7000\ndata: hello\n\n"));

      connected(
          clientBuilder()
              .calculateReconnectDelay(
                  state -> {
                    delays.add(state.serverReconnectDelay());
                    return Duration.ofMillis(1);
                  }));

      awaitUntil(() -> !delays.isEmpty());
      assertThat(snapshot(delays).get(0)).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void the_default_server_delay_is_two_seconds() {
      List<Duration> delays = Collections.synchronizedList(new ArrayList<>());
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(
          clientBuilder()
              .calculateReconnectDelay(
                  state -> {
                    delays.add(state.serverReconnectDelay());
                    return Duration.ofMillis(1);
                  }));

      awaitUntil(() -> !delays.isEmpty());
      assertThat(snapshot(delays).get(0)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void connect_is_ignored_while_already_connected() {
      opener.thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());
      awaitUntil(() -> client.readyState() == ReadyState.OPEN);
      client.connect();
      client.connect();

      assertThat(opener.attemptCount()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("close")
  class Close {

    @Test
    void close_interrupts_a_read_that_is_already_parked() throws InterruptedException {
      FakeResponseStream stream = FakeResponseStream.live(200);
      opener.script(() -> stream).thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());
      awaitUntil(() -> connects.get() == 1);
      stream.awaitParkedRead();

      long start = System.nanoTime();
      client.close();
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
      assertThat(stream.cancelCount()).isEqualTo(1);
      assertThat(client.readyState()).isEqualTo(ReadyState.CLOSED);
    }

    @Test
    void close_releases_the_response() throws InterruptedException {
      FakeResponseStream stream = FakeResponseStream.live(200);
      opener.script(() -> stream).thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());
      awaitUntil(() -> connects.get() == 1);
      stream.awaitParkedRead();
      client.close();

      awaitUntil(() -> stream.closeCount() == 1);
    }

    @Test
    void close_cancels_a_pending_reconnect() {
      opener.thereafter(() -> FakeResponseStream.of(500, ""));

      connected(clientBuilder().calculateReconnectDelay(state -> Duration.ofSeconds(30)));
      awaitUntil(() -> opener.attemptCount() == 1);
      client.close();

      assertThat(client.readyState()).isEqualTo(ReadyState.CLOSED);
      assertThat(opener.attemptCount()).isEqualTo(1);
    }

    @Test
    void close_allows_a_later_connect() {
      opener.thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());
      awaitUntil(() -> connects.get() == 1);
      client.close();
      client.connect();

      awaitUntil(() -> connects.get() == 2);
      assertThat(client.readyState()).isEqualTo(ReadyState.OPEN);
    }

    @Test
    void close_is_idempotent() {
      opener.thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());
      awaitUntil(() -> connects.get() == 1);
      client.close();
      client.close();
      client.close();

      assertThat(client.readyState()).isEqualTo(ReadyState.CLOSED);
    }

    @Test
    void close_before_connect_is_harmless() {
      client = clientBuilder().build();

      client.close();

      assertThat(client.readyState()).isEqualTo(ReadyState.CLOSED);
      assertThat(opener.attemptCount()).isZero();
    }

    @Test
    void close_from_inside_a_handler_does_not_deadlock() {
      FakeResponseStream stream = FakeResponseStream.live(200);
      opener.script(() -> stream).thereafter(() -> FakeResponseStream.live(200));
      CountDownLatch closed = new CountDownLatch(1);

      connected(
          clientBuilder()
              .onMessage(
                  message -> {
                    messages.add(message);
                    client.close();
                    closed.countDown();
                  }));

      awaitUntil(() -> connects.get() == 1);
      stream.push("data: hello\n\n");

      awaitUntil(() -> closed.await(1, TimeUnit.MILLISECONDS));
      assertThat(client.readyState()).isEqualTo(ReadyState.CLOSED);
    }

    @Test
    void does_not_close_an_opener_it_did_not_create() {
      opener.thereafter(() -> FakeResponseStream.live(200));

      connected(clientBuilder());
      awaitUntil(() -> connects.get() == 1);
      client.close();

      assertThat(opener.closeCount()).isZero();
    }
  }

  @Nested
  @DisplayName("concurrent lifecycle")
  class ConcurrentLifecycle {

    @Test
    void simultaneous_connects_open_one_connection() throws InterruptedException {
      opener.thereafter(() -> FakeResponseStream.live(200));
      client = clientBuilder().build();

      int racers = 8;
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch done = new CountDownLatch(racers);
      for (int i = 0; i < racers; i++) {
        Thread racer =
            new Thread(
                () -> {
                  try {
                    start.await();
                    client.connect();
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  } finally {
                    done.countDown();
                  }
                });
        racer.setDaemon(true);
        racer.start();
      }

      start.countDown();
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

      awaitUntil(() -> client.readyState() == ReadyState.OPEN);
      assertThat(opener.attemptCount()).isEqualTo(1);
    }

    @Test
    void a_connection_that_lands_after_close_does_not_reopen_the_client()
        throws InterruptedException {
      CountDownLatch opening = new CountDownLatch(1);
      CountDownLatch mayFinish = new CountDownLatch(1);
      FakeResponseStream stream = FakeResponseStream.live(200);
      opener.thereafter(
          () -> {
            opening.countDown();
            try {
              mayFinish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
            return stream;
          });

      connected(clientBuilder());
      assertThat(opening.await(5, TimeUnit.SECONDS)).isTrue();

      // close() lands while the request is still in flight.
      client.close();
      mayFinish.countDown();

      // The stream that arrives late is released rather than read from.
      awaitUntil(() -> stream.closeCount() >= 1);
      assertThat(client.readyState()).isEqualTo(ReadyState.CLOSED);
      assertThat(connects).hasValue(0);
    }
  }
}
