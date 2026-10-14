package com.configdirector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigEvaluatedEvent;
import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigState;
import com.configdirector.ConnectionMode;
import com.configdirector.Context;
import com.configdirector.testing.TestHttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

class ContextKindsTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final Duration FIRST_FLUSH = Duration.ofSeconds(15);
  private static final AtomicInteger LOGGERS = new AtomicInteger();

  private static final String BUCKETED_CONFIG_ID = "11111111-1111-4111-8111-111111111111";

  private static final String AUDIENCE =
      "\"audience\":{\"id\":\"id-audience\",\"key\":\"audience\",\"type\":\"string\","
          + "\"target\":{\"defaultValue\":\"everyone\",\"defaultValueId\":\"dv-audience\",\"rules\":[{"
          + "\"id\":\"r-audience\",\"type\":\"conditional\",\"order\":1,\"target\":\"value\","
          + "\"value\":\"devices and tenants\",\"valueId\":\"rv-audience\",\"conditions\":[{"
          + "\"id\":\"c-audience\",\"attribute\":\"kind\",\"operator\":\"is one of\","
          + "\"targetType\":\"text\",\"targetValues\":[\"device\",\"tenant\"],\"trait\":null}]}]}}";

  private static final String GREETING =
      "\"greeting\":{\"id\":\"id-greeting\",\"key\":\"greeting\",\"type\":\"string\","
          + "\"target\":{\"defaultValue\":\"hello\",\"defaultValueId\":\"dv-greeting\",\"rules\":[{"
          + "\"id\":\"r-greeting\",\"type\":\"conditional\",\"order\":1,\"target\":\"value\","
          + "\"value\":\"hello user-1\",\"valueId\":\"rv-greeting\",\"conditions\":[{"
          + "\"id\":\"c-greeting\",\"attribute\":\"identifier\",\"operator\":\"=\","
          + "\"targetType\":\"text\",\"targetValues\":[\"user-1\"],\"trait\":null}]}]}}";

  private static final String BUCKETED =
      "\"bucketed\":{\"id\":\""
          + BUCKETED_CONFIG_ID
          + "\",\"key\":\"bucketed\",\"type\":\"string\",\"target\":{\"defaultValue\":\"none\","
          + "\"rules\":[{\"id\":\"r-bucketed\",\"type\":\"percentage\",\"order\":1,\"percentages\":["
          + "{\"id\":\"low\",\"percentage\":59.0,\"value\":\"low\",\"valueId\":\"v-low\"},"
          + "{\"id\":\"high\",\"percentage\":41.0,\"value\":\"high\",\"valueId\":\"v-high\"}]}]}}";

  private static final String BUNDLE =
      "{\"timestamp\":\"t1\",\"configs\":{" + AUDIENCE + "," + GREETING + "," + BUCKETED + "}}";

  private final List<String> requestedPaths = Collections.synchronizedList(new ArrayList<>());

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> appender;
  private ConfigDirectorClient client;
  private TestHttpServer server;

  @BeforeEach
  void attachAppender() {
    logger =
        (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger("context-kinds-under-test-" + LOGGERS.incrementAndGet());
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

  private ConfigDirectorClient readyClient() {
    ConfigDirectorClient started = startedClient();
    started.initialize();
    return started;
  }

  private ConfigDirectorClient startedClient() {
    server =
        start(
            session -> {
              requestedPaths.add(session.path());
              byte[] body = BUNDLE.getBytes(StandardCharsets.UTF_8);
              session.respond(
                  200,
                  "Content-Type: application/json",
                  "Content-Length: " + body.length,
                  "Connection: close");
              session.send(BUNDLE);
              session.close();
            });
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
    return client;
  }

  private static TestHttpServer start(java.util.function.Consumer<TestHttpServer.Session> handler) {
    try {
      return TestHttpServer.start(handler);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  private static Context kinded(String id, String kind) {
    return Context.builder().id(id).kind(kind).build();
  }

  private List<String> errorsLogged() {
    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.ERROR)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private static <T> List<T> snapshot(List<T> shared) {
    synchronized (shared) {
      return new ArrayList<>(shared);
    }
  }

  static Stream<String> invalidKinds() {
    return Stream.of("", "k".repeat(65), "Device", " device", "device ", "dev ice", "dévice");
  }

  @Nested
  @DisplayName("a valid kind")
  class ValidKinds {

    @Test
    void a_kind_condition_targets_the_contexts_of_that_kind() {
      ConfigDirectorClient client = readyClient();

      assertThat(client.getString("audience", "in-code", kinded("42", "device")))
          .isEqualTo("devices and tenants");
      assertThat(client.getString("audience", "in-code", kinded("42", "tenant")))
          .isEqualTo("devices and tenants");
      assertThat(client.getString("audience", "in-code", kinded("42", "user")))
          .isEqualTo("everyone");
      assertThat(client.getString("audience", "in-code", kinded("42", null)))
          .isEqualTo("everyone");
      assertThat(client.getString("audience", "in-code")).isEqualTo("everyone");
    }

    @Test
    void a_user_keeps_its_bucket_and_a_device_with_the_same_identifier_gets_its_own() {
      ConfigDirectorClient client = readyClient();

      assertThat(client.getString("bucketed", "in-code", kinded("user-1", null))).isEqualTo("low");
      assertThat(client.getString("bucketed", "in-code", kinded("user-1", "user")))
          .isEqualTo("low");
      assertThat(client.getString("bucketed", "in-code", kinded("user-1", "device")))
          .isEqualTo("high");
    }

    @Test
    void a_context_of_any_kind_without_an_identifier_lands_in_the_first_bucket() {
      ConfigDirectorClient client = readyClient();

      assertThat(client.getString("bucketed", "in-code", kinded(null, "device"))).isEqualTo("low");
    }

    @Test
    void accepts_sixty_four_characters_of_every_allowed_kind() {
      ConfigDirectorClient client = readyClient();
      String longest = "abcdefghijklmnopqrstuvwxyz-0123456789_abcdefghijklmnopqrstuvwxyz";

      assertThat(longest).hasSize(64);
      assertThat(client.getString("greeting", "in-code", kinded("user-1", longest)))
          .isEqualTo("hello user-1");
      assertThat(errorsLogged()).isEmpty();
    }

    @Test
    void the_evaluation_carries_the_context_as_supplied() {
      ConfigDirectorClient client = readyClient();
      List<ConfigEvaluatedEvent> events = Collections.synchronizedList(new ArrayList<>());
      client.onConfigEvaluated(events::add);
      Context device = kinded("user-1", "device");

      client.getString("greeting", "in-code", device);

      assertThat(snapshot(events).get(0).evaluation().context()).isEqualTo(device);
    }
  }

  @Nested
  @DisplayName("an invalid kind")
  class InvalidKinds {

    private ConfigEvaluation lastEvaluation(List<ConfigEvaluatedEvent> events) {
      List<ConfigEvaluatedEvent> seen = snapshot(events);
      return seen.get(seen.size() - 1).evaluation();
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @MethodSource("com.configdirector.client.ContextKindsTest#invalidKinds")
    void evaluates_exactly_as_if_no_context_was_supplied(String invalidKind) {
      ConfigDirectorClient client = readyClient();
      List<ConfigEvaluatedEvent> events = Collections.synchronizedList(new ArrayList<>());
      client.onConfigEvaluated(events::add);

      String withoutContext = client.getString("greeting", "in-code");
      ConfigEvaluation expected = lastEvaluation(events);
      String withInvalidKind = client.getString("greeting", "in-code", kinded("user-1", invalidKind));
      ConfigEvaluation actual = lastEvaluation(events);

      assertThat(withoutContext).isEqualTo("hello");
      assertThat(withInvalidKind).isEqualTo(withoutContext);
      assertThat(actual.reason()).isEqualTo(expected.reason());
      assertThat(actual.isDefault()).isEqualTo(expected.isDefault());
      assertThat(actual.valueId()).isEqualTo(expected.valueId());
      assertThat(actual.context()).isNull();
    }

    @Test
    void takes_the_bucket_of_no_context_rather_than_the_in_code_default() {
      ConfigDirectorClient client = readyClient();

      assertThat(client.getString("bucketed", "in-code", kinded("user-1", "Device")))
          .isEqualTo("low");
    }

    @Test
    void every_typed_getter_evaluates_as_no_context() {
      ConfigDirectorClient client = readyClient();
      Context invalid = kinded("user-1", "Tenant");

      assertThat(client.getString("greeting", "in-code", invalid)).isEqualTo("hello");
      assertThat(client.getValue("greeting", "in-code", invalid)).isEqualTo("hello");
      assertThat(client.getString("audience", "in-code", kinded("1", "Device")))
          .isEqualTo("everyone");
    }

    @Test
    void get_all_configs_evaluates_every_config_as_no_context() {
      ConfigDirectorClient client = readyClient();

      Map<String, ConfigState> all = client.getAllConfigs(kinded("user-1", "Device"));

      assertThat(all.get("greeting").value()).isEqualTo("hello");
      assertThat(all.get("bucketed").value()).isEqualTo("low");
    }

    @Test
    void a_watcher_registered_with_it_receives_the_value_for_no_context() {
      List<String> seen = Collections.synchronizedList(new ArrayList<>());
      ConfigDirectorClient client = startedClient();
      client.watchString("greeting", "in-code", seen::add, kinded("user-1", "Device"));

      client.initialize();

      await().atMost(TIMEOUT).until(() -> !snapshot(seen).isEmpty());
      assertThat(snapshot(seen).get(0)).isEqualTo("hello");
    }
  }

  @Nested
  @DisplayName("logging an invalid kind")
  class Logging {

    @Test
    void logs_an_error_naming_the_kind() {
      readyClient().getString("greeting", "in-code", kinded("user-1", "Tenant"));

      assertThat(errorsLogged())
          .containsExactly(
              "[ConfigDirectorClient] Ignored a context with the invalid kind 'Tenant' and"
                  + " evaluated as if no context was supplied. A context kind has 1 to 64"
                  + " characters, using only lowercase letters, digits, '-' and '_'.");
    }

    @Test
    void logs_a_kind_once_however_often_it_is_read() {
      ConfigDirectorClient client = readyClient();

      client.getString("greeting", "in-code", kinded("user-1", "Tenant"));
      client.getString("greeting", "in-code", kinded("user-2", "Tenant"));
      client.getAllConfigs(kinded("user-3", "Tenant"));

      assertThat(errorsLogged()).hasSize(1);
    }

    @Test
    void logs_each_distinct_kind() {
      ConfigDirectorClient client = readyClient();

      client.getString("greeting", "in-code", kinded("user-1", "Tenant"));
      client.getString("greeting", "in-code", kinded("user-1", "Device"));

      assertThat(errorsLogged()).hasSize(2);
      assertThat(errorsLogged().get(1)).contains("'Device'");
    }

    @Test
    void says_once_that_further_kinds_are_not_logged_after_twenty() {
      ConfigDirectorClient client = readyClient();

      IntStream.range(0, 25)
          .forEach(i -> client.getString("greeting", "in-code", kinded("user-1", "Kind" + i)));

      List<String> errors = errorsLogged();
      assertThat(errors).hasSize(21);
      assertThat(errors.subList(0, 20)).allMatch(message -> message.contains("invalid kind 'Kind"));
      assertThat(errors.get(20))
          .isEqualTo(
              "[ConfigDirectorClient] Ignored a context with another invalid kind and evaluated as"
                  + " if no context was supplied. 20 different invalid kinds were logged recently,"
                  + " so further invalid kinds are not logged individually.");
    }

    @Test
    void cuts_a_long_kind_to_its_first_128_characters() {
      String kind = "k".repeat(127) + "😀" + "tail";

      readyClient().getString("greeting", "in-code", kinded("user-1", kind));

      assertThat(errorsLogged().get(0))
          .contains("'" + "k".repeat(127) + "😀" + "'");
    }

    @Test
    void logs_a_kind_again_after_the_next_telemetry_flush() {
      ConfigDirectorClient client = readyClient();
      client.getString("greeting", "in-code", kinded("user-1", "Tenant"));

      await()
          .atMost(FIRST_FLUSH)
          .until(() -> snapshot(requestedPaths).stream().anyMatch(path -> path.endsWith("/server/telemetry/v1")));
      client.getString("greeting", "in-code", kinded("user-1", "Tenant"));

      assertThat(errorsLogged()).hasSize(2);
    }
  }
}
