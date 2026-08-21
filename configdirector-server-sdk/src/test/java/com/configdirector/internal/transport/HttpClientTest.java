package com.configdirector.internal.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.configdirector.testing.TestHttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpClientTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient client = new HttpClient();

  @AfterEach
  void tearDown() {
    client.close();
  }

  private static TestHttpServer start(Consumer<TestHttpServer.Session> handler) {
    try {
      return TestHttpServer.start(handler);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static void respond(TestHttpServer.Session session, int status, String body) {
    session.respond(
        status,
        "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length,
        "Connection: close");
    session.send(body);
    session.close();
  }

  private HttpResponse post(TestHttpServer server, byte[] body) {
    return client.post(server.url("/x"), body, Map.of("X-Test", "yes"), TIMEOUT);
  }

  @Test
  void returns_the_status_and_body() throws Exception {
    try (TestHttpServer server = start(session -> respond(session, 200, "{\"ok\":true}"))) {
      HttpResponse response = post(server, "{}".getBytes(StandardCharsets.UTF_8));

      assertThat(response.status()).isEqualTo(200);
      assertThat(response.body()).isEqualTo("{\"ok\":true}");
      assertThat(response.ok()).isTrue();
    }
  }

  @Test
  void sends_the_body_headers_and_method() throws Exception {
    java.util.concurrent.BlockingQueue<String> seen = new java.util.concurrent.LinkedBlockingQueue<>();
    try (TestHttpServer server =
        start(
            session -> {
              seen.add(session.method() + " " + session.header("X-Test") + " " + session.bodyAsString());
              respond(session, 200, "{}");
            })) {
      post(server, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

      assertThat(seen.poll(5, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo("POST yes {\"a\":1}");
    }
  }

  @Test
  void sets_a_json_content_type() throws Exception {
    java.util.concurrent.BlockingQueue<String> types = new java.util.concurrent.LinkedBlockingQueue<>();
    try (TestHttpServer server =
        start(
            session -> {
              types.add(session.header("Content-Type"));
              respond(session, 200, "{}");
            })) {
      post(server, "{}".getBytes(StandardCharsets.UTF_8));

      assertThat(types.poll(5, java.util.concurrent.TimeUnit.SECONDS)).startsWith("application/json");
    }
  }

  @Test
  void an_error_status_is_returned_rather_than_thrown() throws Exception {
    try (TestHttpServer server = start(session -> respond(session, 503, "unavailable"))) {
      HttpResponse response = post(server, "{}".getBytes(StandardCharsets.UTF_8));

      assertThat(response.status()).isEqualTo(503);
      assertThat(response.body()).isEqualTo("unavailable");
      assertThat(response.ok()).isFalse();
    }
  }

  @Test
  void a_no_content_response_has_an_empty_body() throws Exception {
    try (TestHttpServer server =
        start(
            session -> {
              session.respond(204);
              session.close();
            })) {
      HttpResponse response = post(server, "{}".getBytes(StandardCharsets.UTF_8));

      assertThat(response.status()).isEqualTo(204);
      assertThat(response.body()).isEmpty();
    }
  }

  @Test
  void a_refused_connection_is_a_connection_failure() {
    assertThatExceptionOfType(ConfigDirectorConnectionException.class)
        .isThrownBy(() -> client.post("http://127.0.0.1:1/x", new byte[0], Map.of(), TIMEOUT))
        .withMessageContaining("Connection failed with error");
  }

  @Test
  void an_unusable_url_is_reported_separately_from_a_network_failure() {
    // The transport treats this as unrecoverable, where a network failure is worth retrying.
    assertThatExceptionOfType(UnusableUrlException.class)
        .isThrownBy(() -> client.post("not-a-url", new byte[0], Map.of(), TIMEOUT))
        .withMessageContaining("not usable");
  }

  @Test
  void a_body_beyond_the_cap_is_discarded_rather_than_held_in_memory() throws Exception {
    // Streams past the cap without ever declaring a length, which is what a hostile endpoint does.
    try (TestHttpServer server =
        start(
            session -> {
              session.respond(200, "Content-Type: application/json");
              String chunk = "x".repeat(1024 * 1024);
              try {
                for (int i = 0; i < 40; i++) {
                  session.send(chunk);
                }
              } catch (RuntimeException expected) {
                // The client hangs up once it has seen enough.
              }
              session.close();
            })) {
      assertThatExceptionOfType(ConfigDirectorConnectionException.class)
          .isThrownBy(() -> post(server, "{}".getBytes(StandardCharsets.UTF_8)))
          .withMessageContaining("byte limit");
    }
  }

  @Test
  void rejects_a_null_client() {
    assertThatNullPointerException().isThrownBy(() -> new HttpClient(null));
  }
}
