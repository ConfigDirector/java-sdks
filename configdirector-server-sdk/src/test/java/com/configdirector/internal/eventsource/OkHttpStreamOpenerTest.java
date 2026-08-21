package com.configdirector.internal.eventsource;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.testing.TestHttpServer;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OkHttpStreamOpenerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final OkHttpStreamOpener opener = new OkHttpStreamOpener();

  @AfterEach
  void tearDown() {
    opener.close();
  }

  private static StreamRequest request(String url) {
    return new StreamRequest(
        url,
        "GET",
        Map.of("Accept", "text/event-stream"),
        null,
        Duration.ofSeconds(5),
        Duration.ZERO,
        true);
  }

  private static String readAll(ResponseStream stream) {
    StringBuilder text = new StringBuilder();
    char[] buffer = new char[256];
    int count;
    while ((count = stream.read(buffer)) >= 0) {
      text.append(buffer, 0, count);
    }
    return text.toString();
  }

  @Test
  void reads_a_body_the_server_writes_a_piece_at_a_time() throws Exception {
    try (TestHttpServer.Queued server = TestHttpServer.startQueued()) {
      AtomicReference<ResponseStream> opened = new AtomicReference<>();
      Thread client = openOn(server.url("/stream"), opened);

      TestHttpServer.Session session = server.next();
      session.respondStreaming();
      client.join(TIMEOUT.toMillis());

      ResponseStream stream = opened.get();
      assertThat(stream.status()).isEqualTo(200);

      session.send("data: one\n\n");
      assertThat(readAtLeast(stream, "data: one\n\n".length())).isEqualTo("data: one\n\n");

      session.send("data: two\n\n");
      assertThat(readAtLeast(stream, "data: two\n\n".length())).isEqualTo("data: two\n\n");

      session.close();
      assertThat(stream.read(new char[64])).isEqualTo(-1);
      stream.close();
    }
  }

  @Test
  void sends_the_method_headers_and_body() throws Exception {
    try (TestHttpServer.Queued server = TestHttpServer.startQueued()) {
      byte[] body = "{\"sdkKey\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
      StreamRequest request =
          new StreamRequest(
              server.url("/stream"),
              "POST",
              Map.of("Accept", "text/event-stream", "Last-Event-ID", "77"),
              body,
              Duration.ofSeconds(5),
              Duration.ZERO,
              true);

      AtomicReference<ResponseStream> opened = new AtomicReference<>();
      Thread client = openOn(request, opened);

      TestHttpServer.Session session = server.next();
      assertThat(session.method()).isEqualTo("POST");
      assertThat(session.header("Accept")).isEqualTo("text/event-stream");
      assertThat(session.header("Last-Event-ID")).isEqualTo("77");
      assertThat(session.bodyAsString()).isEqualTo("{\"sdkKey\":\"abc\"}");

      session.respondStreaming();
      client.join(TIMEOUT.toMillis());
      opened.get().close();
    }
  }

  @Test
  void an_error_status_is_returned_rather_than_thrown() throws Exception {
    try (TestHttpServer server =
        TestHttpServer.start(
            session -> {
              session.respond(503, "Content-Length: 0");
              session.close();
            })) {

      try (ResponseStream stream = opener.open(request(server.url("/stream")))) {
        assertThat(stream.status()).isEqualTo(503);
      }
    }
  }

  @Test
  void no_content_is_returned_as_a_status() throws Exception {
    try (TestHttpServer server =
        TestHttpServer.start(
            session -> {
              session.respond(204);
              session.close();
            })) {

      try (ResponseStream stream = opener.open(request(server.url("/stream")))) {
        assertThat(stream.status()).isEqualTo(204);
      }
    }
  }

  @Test
  void a_redirect_is_followed_by_default() throws Exception {
    try (TestHttpServer server = TestHttpServer.start(OkHttpStreamOpenerTest::redirectOnce)) {
      try (ResponseStream stream = opener.open(request(server.url("/start")))) {
        assertThat(stream.status()).isEqualTo(200);
        assertThat(readAll(stream)).isEqualTo("data: arrived\n\n");
      }
      assertThat(server.connectionCount()).isEqualTo(2);
    }
  }

  @Test
  void a_redirect_is_a_status_when_following_is_disabled() throws Exception {
    try (TestHttpServer server = TestHttpServer.start(OkHttpStreamOpenerTest::redirectOnce)) {
      StreamRequest request =
          new StreamRequest(
              server.url("/start"),
              "GET",
              Map.of(),
              null,
              Duration.ofSeconds(5),
              Duration.ZERO,
              false);

      try (ResponseStream stream = opener.open(request)) {
        assertThat(stream.status()).isEqualTo(302);
      }
    }
  }

  private static void redirectOnce(TestHttpServer.Session session) {
    if (session.path().equals("/start")) {
      // Connection: close, or OkHttp would keep the socket for the follow-up and find it shut.
      session.respond(302, "Location: /stream", "Content-Length: 0", "Connection: close");
    } else {
      session.respondStreaming();
      session.send("data: arrived\n\n");
    }
    session.close();
  }

  @Test
  void cancel_unblocks_a_parked_read() throws Exception {
    try (TestHttpServer.Queued server = TestHttpServer.startQueued()) {
      AtomicReference<ResponseStream> opened = new AtomicReference<>();
      Thread client = openOn(server.url("/stream"), opened);
      TestHttpServer.Session session = server.next();
      session.respondStreaming();
      client.join(TIMEOUT.toMillis());

      ResponseStream stream = opened.get();
      assertThat(stream).isNotNull();

      CountDownLatch parked = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(1);
      AtomicReference<Throwable> thrown = new AtomicReference<>();
      Thread reader =
          new Thread(
              () -> {
                parked.countDown();
                try {
                  stream.read(new char[64]);
                } catch (Throwable error) {
                  thrown.set(error);
                } finally {
                  finished.countDown();
                }
              });
      reader.setDaemon(true);
      reader.start();

      assertThat(parked.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(200);
      stream.cancel();

      assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(thrown.get()).isInstanceOf(StreamClosedException.class);
      stream.close();
    }
  }

  @Test
  void a_silent_stream_past_the_read_timeout_is_reported_as_stalled() throws Exception {
    try (TestHttpServer.Queued server = TestHttpServer.startQueued()) {
      StreamRequest request =
          new StreamRequest(
              server.url("/stream"),
              "GET",
              Map.of(),
              null,
              Duration.ofSeconds(5),
              Duration.ofMillis(250),
              true);

      AtomicReference<ResponseStream> opened = new AtomicReference<>();
      Thread client = openOn(request, opened);
      TestHttpServer.Session session = server.next();
      session.respondStreaming();
      client.join(TIMEOUT.toMillis());

      try (ResponseStream stream = opened.get()) {
        assertThatExceptionOfType(StreamStalledException.class)
            .isThrownBy(() -> stream.read(new char[64]));
      }
    }
  }

  @Test
  void a_multibyte_character_split_across_reads_survives() throws Exception {
    try (TestHttpServer.Queued server = TestHttpServer.startQueued()) {
      AtomicReference<ResponseStream> opened = new AtomicReference<>();
      Thread client = openOn(server.url("/stream"), opened);
      TestHttpServer.Session session = server.next();
      session.respondStreaming();
      client.join(TIMEOUT.toMillis());
      ResponseStream stream = opened.get();

      StringBuilder collected = new StringBuilder();
      Thread reader =
          new Thread(
              () -> {
                char[] buffer = new char[64];
                int count;
                try {
                  while ((count = stream.read(buffer)) >= 0) {
                    synchronized (collected) {
                      collected.append(buffer, 0, count);
                    }
                  }
                } catch (EventSourceException ended) {
                  // The socket closing is how this test ends.
                }
              });
      reader.setDaemon(true);
      reader.start();

      // The two halves of a single U+00E9, deliberately in separate flushes.
      session.send((byte) 0xC3);
      Thread.sleep(200);
      session.send((byte) 0xA9, (byte) '\n');

      await()
          .atMost(TIMEOUT)
          .until(
              () -> {
                synchronized (collected) {
                  return collected.toString().equals("é\n");
                }
              });
      stream.cancel();
      stream.close();
    }
  }

  @Test
  void uses_a_supplied_client_and_leaves_it_usable_after_close() throws Exception {
    AtomicInteger intercepted = new AtomicInteger();
    OkHttpClient shared =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  intercepted.incrementAndGet();
                  return chain.proceed(chain.request());
                })
            .build();

    try (TestHttpServer server =
        TestHttpServer.start(
            session -> {
              session.respond(204);
              session.close();
            })) {

      OkHttpStreamOpener supplied = new OkHttpStreamOpener(shared);
      try (ResponseStream stream = supplied.open(request(server.url("/stream")))) {
        assertThat(stream.status()).isEqualTo(204);
      }
      assertThat(intercepted).hasValue(1);

      // close() evicts pooled connections rather than shutting the client down, so a caller that
      // shared its own client still has a working one.
      supplied.close();
      try (ResponseStream stream = supplied.open(request(server.url("/stream")))) {
        assertThat(stream.status()).isEqualTo(204);
      }
      assertThat(intercepted).hasValue(2);
    }
  }

  @Test
  void rejects_a_null_client() {
    assertThatNullPointerException().isThrownBy(() -> new OkHttpStreamOpener(null));
  }

  @Test
  void a_connection_that_cannot_be_opened_throws() {
    // Port 1 on loopback refuses connections.
    assertThatExceptionOfType(StreamConnectException.class)
        .isThrownBy(() -> opener.open(request("http://127.0.0.1:1/stream")))
        .withCauseInstanceOf(IOException.class);
  }

  private Thread openOn(String url, AtomicReference<ResponseStream> target) {
    return openOn(request(url), target);
  }

  private Thread openOn(StreamRequest request, AtomicReference<ResponseStream> target) {
    Thread thread = new Thread(() -> target.set(opener.open(request)));
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** TCP may split one write across reads, so gather until the expected amount has arrived. */
  private static String readAtLeast(ResponseStream stream, int chars) {
    StringBuilder text = new StringBuilder();
    char[] buffer = new char[256];
    while (text.length() < chars) {
      int count = stream.read(buffer);
      if (count < 0) {
        break;
      }
      text.append(buffer, 0, count);
    }
    return text.toString();
  }
}
