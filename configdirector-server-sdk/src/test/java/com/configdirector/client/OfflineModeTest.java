package com.configdirector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.configdirector.ClientReadyEvent;
import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorInitializationException;
import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigState;
import com.configdirector.ConfigType;
import com.configdirector.ConnectionMode;
import com.configdirector.ConnectionOptions;
import com.configdirector.Context;
import com.configdirector.EvaluationReason;
import com.configdirector.testing.TestHttpServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class OfflineModeTest {

  private static final AtomicInteger LOGGERS = new AtomicInteger();

  @TempDir Path directory;

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> appender;
  private ConfigDirectorClient client;
  private TestHttpServer server;

  @BeforeEach
  void attachAppender() {
    logger =
        (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger("offline-mode-under-test-" + LOGGERS.incrementAndGet());
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

  private ConfigDirectorClient offlineClient(Consumer<ConnectionOptions.Builder> connection) {
    client =
        ConfigDirector.client(
            options ->
                options
                    .logger(logger)
                    .connection(
                        builder -> connection.accept(builder.mode(ConnectionMode.OFFLINE))));
    return client;
  }

  private ConfigDirectorClient clientConnectingToTheServer(
      Consumer<ConnectionOptions.Builder> connection) throws IOException {
    String url = serverClosingEveryConnection().url("/");
    client =
        ConfigDirector.client(
            "sdk-key",
            options -> options.connection(builder -> connection.accept(builder.url(url))));
    return client;
  }

  private TestHttpServer serverClosingEveryConnection() throws IOException {
    server = TestHttpServer.start(TestHttpServer.Session::close);
    return server;
  }

  private Path fileHolding(String json) throws IOException {
    return Files.writeString(directory.resolve("flags.json"), json);
  }

  private static Map<String, ConfigType> typesOf(Map<String, ConfigState> configs) {
    return configs.values().stream()
        .collect(Collectors.toMap(ConfigState::key, ConfigState::type));
  }

  @Nested
  @DisplayName("serving values")
  class ServingValues {

    @Test
    void inline_values_serve_each_value_with_its_type() {
      offlineClient(
          connection ->
              connection.values(
                  Map.of(
                      "new-checkout", true,
                      "max-items", 20,
                      "max-bytes", 5_000_000_000L,
                      "discount", 0.25,
                      "ratio", 0.5f,
                      "greeting", "hello",
                      "theme", Map.of("color", "blue"),
                      "regions", List.of("eu", "us"))));

      client.initialize();

      assertThat(client.getBoolean("new-checkout", false)).isTrue();
      assertThat(client.getInteger("max-items", 0)).isEqualTo(20);
      assertThat(client.getValue("max-bytes", 0L)).isEqualTo(5_000_000_000L);
      assertThat(client.getDouble("discount", 0.0)).isEqualTo(0.25);
      assertThat(client.getValue("ratio", 0.0f)).isEqualTo(0.5f);
      assertThat(client.getString("greeting", "")).isEqualTo("hello");
      assertThat(client.getJsonObject("theme", Map.of())).isEqualTo(Map.of("color", "blue"));
      assertThat(client.getJsonArray("regions", List.of())).isEqualTo(List.of("eu", "us"));
      assertThat(typesOf(client.getAllConfigs()))
          .isEqualTo(
              Map.of(
                  "new-checkout", ConfigType.BOOLEAN,
                  "max-items", ConfigType.INTEGER,
                  "max-bytes", ConfigType.INTEGER,
                  "discount", ConfigType.FLOAT,
                  "ratio", ConfigType.FLOAT,
                  "greeting", ConfigType.STRING,
                  "theme", ConfigType.JSON,
                  "regions", ConfigType.JSON));
    }

    @Test
    void a_file_serves_each_value_with_its_type() throws IOException {
      Path file =
          fileHolding(
              "{\"new-checkout\": true, \"max-items\": 20, \"discount\": 0.25,"
                  + " \"greeting\": \"hello\", \"theme\": {\"color\": \"blue\"},"
                  + " \"regions\": [\"eu\", \"us\"]}");
      offlineClient(connection -> connection.file(file));

      client.initialize();

      assertThat(client.getBoolean("new-checkout", false)).isTrue();
      assertThat(client.getInteger("max-items", 0)).isEqualTo(20);
      assertThat(client.getDouble("discount", 0.0)).isEqualTo(0.25);
      assertThat(client.getString("greeting", "")).isEqualTo("hello");
      assertThat(client.getJsonObject("theme", Map.of())).isEqualTo(Map.of("color", "blue"));
      assertThat(client.getJsonArray("regions", List.of())).isEqualTo(List.of("eu", "us"));
      assertThat(typesOf(client.getAllConfigs()))
          .isEqualTo(
              Map.of(
                  "new-checkout", ConfigType.BOOLEAN,
                  "max-items", ConfigType.INTEGER,
                  "discount", ConfigType.FLOAT,
                  "greeting", ConfigType.STRING,
                  "theme", ConfigType.JSON,
                  "regions", ConfigType.JSON));
    }

    @Test
    void no_source_serves_every_in_code_default_value_with_config_state_missing() {
      List<ConfigEvaluation> evaluations = new ArrayList<>();
      offlineClient(connection -> {});
      client.onConfigEvaluated(event -> evaluations.add(event.evaluation()));

      client.initialize();
      boolean newCheckout = client.getBoolean("new-checkout", true);
      int maxItems = client.getInteger("max-items", 10);

      assertThat(client.isReady()).isTrue();
      assertThat(newCheckout).isTrue();
      assertThat(maxItems).isEqualTo(10);
      assertThat(evaluations)
          .extracting(ConfigEvaluation::reason)
          .containsExactly(
              EvaluationReason.CONFIG_STATE_MISSING, EvaluationReason.CONFIG_STATE_MISSING);
    }

    @Test
    void a_served_value_is_a_found_match() {
      List<ConfigEvaluation> evaluations = new ArrayList<>();
      offlineClient(connection -> connection.values(Map.of("new-checkout", true)));
      client.onConfigEvaluated(event -> evaluations.add(event.evaluation()));
      client.initialize();

      client.getBoolean("new-checkout", false);

      assertThat(evaluations)
          .extracting(ConfigEvaluation::reason)
          .containsExactly(EvaluationReason.FOUND_MATCH);
    }

    @Test
    void every_context_receives_the_same_value() {
      offlineClient(connection -> connection.values(Map.of("greeting", "hello from the file")));
      client.initialize();

      String withoutContext = client.getString("greeting", "hello");
      String forUser = client.getString("greeting", "hello", Context.builder().id("user-1").build());
      String forDevice =
          client.getString(
              "greeting", "hello", Context.builder().id("device-9").kind("device").build());

      assertThat(withoutContext).isEqualTo("hello from the file");
      assertThat(forUser).isEqualTo("hello from the file");
      assertThat(forDevice).isEqualTo("hello from the file");
    }

    @Test
    void a_value_read_as_another_type_serves_the_in_code_default_with_type_mismatch() {
      List<ConfigEvaluation> evaluations = new ArrayList<>();
      offlineClient(connection -> connection.values(Map.of("max-items", 20)));
      client.onConfigEvaluated(event -> evaluations.add(event.evaluation()));
      client.initialize();

      String maxItems = client.getString("max-items", "unlimited");

      assertThat(maxItems).isEqualTo("unlimited");
      assertThat(evaluations)
          .extracting(ConfigEvaluation::reason)
          .containsExactly(EvaluationReason.TYPE_MISMATCH);
    }
  }

  @Nested
  @DisplayName("readiness")
  class Readiness {

    @Test
    void initialize_makes_the_client_ready_at_once_and_fires_client_ready_once() {
      List<ClientReadyEvent> readyEvents = new ArrayList<>();
      offlineClient(
          connection ->
              connection.values(Map.of("new-checkout", true)).timeout(Duration.ofSeconds(30)));
      client.onClientReady(readyEvents::add);

      long started = System.nanoTime();
      client.initialize();
      Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

      assertThat(client.isReady()).isTrue();
      assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
      assertThat(readyEvents).hasSize(1);
    }

    @Test
    void a_watch_is_called_once_with_the_offline_value_and_never_again() {
      List<Boolean> watched = new ArrayList<>();
      offlineClient(connection -> connection.values(Map.of("new-checkout", true)));
      client.watchBoolean("new-checkout", false, watched::add);

      client.initialize();
      client.getBoolean("new-checkout", false);
      client.getAllConfigs();

      assertThat(watched).containsExactly(true);
    }

    @Test
    void initializing_from_two_threads_at_once_fires_client_ready_once() throws InterruptedException {
      List<ClientReadyEvent> readyEvents = new CopyOnWriteArrayList<>();
      CountDownLatch bothDelivered = new CountDownLatch(2);
      offlineClient(connection -> connection.values(Map.of("new-checkout", true)));
      client.onClientReady(readyEvents::add);
      client.onConfigsUpdated(event -> awaitTheOther(bothDelivered));
      Thread other = new Thread(client::initialize);

      other.start();
      client.initialize();
      other.join(TimeUnit.SECONDS.toMillis(5));

      assertThat(readyEvents).hasSize(1);
    }

    private void awaitTheOther(CountDownLatch bothDelivered) {
      bothDelivered.countDown();
      try {
        assertThat(bothDelivered.await(5, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    @Test
    void initializing_again_reads_the_file_again() throws IOException {
      Path file = fileHolding("{\"max-items\": 20}");
      offlineClient(connection -> connection.file(file));
      client.initialize();
      Files.writeString(file, "{\"max-items\": 30}");

      client.initialize();

      assertThat(client.getInteger("max-items", 0)).isEqualTo(30);
    }

    @Test
    void initializing_again_calls_the_watches_again_without_firing_client_ready()
        throws IOException {
      Path file = fileHolding("{\"max-items\": 20}");
      List<Integer> watched = new ArrayList<>();
      List<ClientReadyEvent> readyEvents = new ArrayList<>();
      offlineClient(connection -> connection.file(file));
      client.watchInteger("max-items", 0, watched::add);
      client.onClientReady(readyEvents::add);
      client.initialize();
      Files.writeString(file, "{\"max-items\": 30}");

      client.initialize();

      assertThat(watched).containsExactly(20, 30);
      assertThat(readyEvents).hasSize(1);
    }

    @Test
    void a_later_initialize_that_cannot_read_the_file_keeps_the_values_read_before()
        throws IOException {
      Path file = fileHolding("{\"max-items\": 20}");
      offlineClient(connection -> connection.file(file));
      client.initialize();
      Files.delete(file);

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize);
      assertThat(client.isReady()).isTrue();
      assertThat(client.getInteger("max-items", 0)).isEqualTo(20);
    }
  }

  @Nested
  @DisplayName("rejecting initialization")
  class Rejections {

    @Test
    void a_missing_file_rejects_initialization() {
      Path missing = directory.resolve("missing.json");
      offlineClient(connection -> connection.file(missing));

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize)
          .withMessageContaining(missing.toString());
      assertThat(client.isReady()).isFalse();
    }

    @Test
    void an_unreadable_file_rejects_initialization() {
      offlineClient(connection -> connection.file(directory));

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize)
          .withMessageContaining(directory.toString());
      assertThat(client.isReady()).isFalse();
    }

    @Test
    void a_file_that_is_not_json_rejects_initialization() throws IOException {
      Path file = fileHolding("new-checkout: true");
      offlineClient(connection -> connection.file(file));

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize)
          .withMessageContaining(file.toString());
      assertThat(client.isReady()).isFalse();
    }

    @Test
    void a_file_with_text_after_its_json_object_rejects_initialization() throws IOException {
      Path file = fileHolding("{\"new-checkout\": true} {\"max-items\": 20}");
      offlineClient(connection -> connection.file(file));

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize)
          .withMessageContaining(file.toString());
      assertThat(client.isReady()).isFalse();
    }

    @Test
    void a_file_holding_an_array_rejects_initialization() throws IOException {
      Path file = fileHolding("[true, 20]");
      offlineClient(connection -> connection.file(file));

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize)
          .withMessageContaining(file.toString());
      assertThat(client.isReady()).isFalse();
    }

    @Test
    void a_file_holding_a_null_value_rejects_initialization() throws IOException {
      Path file = fileHolding("{\"new-checkout\": true, \"max-items\": null}");
      offlineClient(connection -> connection.file(file));

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize)
          .withMessageContaining(file.toString())
          .withMessageContaining("max-items");
      assertThat(client.isReady()).isFalse();
    }

    @Test
    void an_inline_null_value_rejects_initialization() {
      Map<String, Object> values = new HashMap<>();
      values.put("max-items", null);
      offlineClient(connection -> connection.values(values));

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize)
          .withMessageContaining("max-items");
      assertThat(client.isReady()).isFalse();
    }

    @Test
    void an_inline_value_of_an_unsupported_type_rejects_initialization() {
      offlineClient(connection -> connection.values(Map.of("launch-day", Duration.ofDays(1))));

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize)
          .withMessageContaining("launch-day");
      assertThat(client.isReady()).isFalse();
    }

    @Test
    void a_file_and_values_together_reject_initialization() throws IOException {
      Path file = fileHolding("{\"new-checkout\": true}");
      offlineClient(connection -> connection.file(file).values(Map.of("new-checkout", false)));

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize);
      assertThat(client.isReady()).isFalse();
    }

    @Test
    void a_file_in_streaming_mode_rejects_initialization_without_connecting() throws IOException {
      Path file = fileHolding("{\"new-checkout\": true}");
      clientConnectingToTheServer(
          connection -> connection.mode(ConnectionMode.STREAMING).file(file));

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize);
      assertThat(client.isReady()).isFalse();
      client.close();
      assertThat(server.connectionCount()).isZero();
    }

    @Test
    void values_in_polling_mode_reject_initialization_without_connecting() throws IOException {
      clientConnectingToTheServer(
          connection -> connection.mode(ConnectionMode.POLLING).values(Map.of("new-checkout", true)));

      assertThatExceptionOfType(ConfigDirectorInitializationException.class)
          .isThrownBy(client::initialize);
      assertThat(client.isReady()).isFalse();
      client.close();
      assertThat(server.connectionCount()).isZero();
    }
  }

  @Nested
  @DisplayName("the server SDK key")
  class ServerSdkKey {

    @Test
    void a_missing_sdk_key_is_accepted_in_offline_mode() {
      client =
          ConfigDirector.client(
              options ->
                  options.connection(
                      connection ->
                          connection
                              .mode(ConnectionMode.OFFLINE)
                              .values(Map.of("new-checkout", true))));

      client.initialize();

      assertThat(client.getBoolean("new-checkout", false)).isTrue();
    }

    @Test
    void a_null_sdk_key_is_accepted_in_offline_mode() {
      client =
          ConfigDirector.client(
              (String) null,
              options ->
                  options.connection(
                      connection ->
                          connection
                              .mode(ConnectionMode.OFFLINE)
                              .values(Map.of("new-checkout", true))));

      client.initialize();

      assertThat(client.getBoolean("new-checkout", false)).isTrue();
    }

    @Test
    void a_blank_sdk_key_is_accepted_in_offline_mode() {
      client =
          ConfigDirector.client(
              "  ",
              options ->
                  options.connection(
                      connection ->
                          connection
                              .mode(ConnectionMode.OFFLINE)
                              .values(Map.of("new-checkout", true))));

      client.initialize();

      assertThat(client.getBoolean("new-checkout", false)).isTrue();
    }

    @Test
    void a_missing_sdk_key_is_still_rejected_in_streaming_mode() {
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(
              () ->
                  ConfigDirector.client(
                      options ->
                          options.connection(connection -> connection.mode(ConnectionMode.STREAMING))));
    }

    @Test
    void a_null_sdk_key_is_still_rejected_in_polling_mode() {
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(
              () ->
                  ConfigDirector.client(
                      (String) null,
                      options ->
                          options.connection(connection -> connection.mode(ConnectionMode.POLLING))));
    }
  }

  @Nested
  @DisplayName("staying offline")
  class StayingOffline {

    @Test
    void sends_nothing_and_opens_no_connection() throws IOException {
      String url = serverClosingEveryConnection().url("/");
      offlineClient(connection -> connection.values(Map.of("new-checkout", true)).url(url));

      client.initialize();
      client.getBoolean("new-checkout", false);
      client.getInteger("max-items", 10);
      client.close();

      assertThat(server.connectionCount()).isZero();
    }

    @Test
    void an_unusable_url_is_ignored() {
      offlineClient(connection -> connection.values(Map.of("new-checkout", true)).url("not-a-url"));

      client.initialize();

      assertThat(client.getBoolean("new-checkout", false)).isTrue();
    }

    @Test
    void a_non_positive_connection_timeout_is_ignored() {
      offlineClient(
          connection -> connection.values(Map.of("new-checkout", true)).timeout(Duration.ZERO));

      client.initialize();

      assertThat(client.getBoolean("new-checkout", false)).isTrue();
    }

    @Test
    void a_non_positive_initialize_timeout_is_ignored() {
      offlineClient(connection -> connection.values(Map.of("new-checkout", true)));

      client.initialize(Duration.ZERO);

      assertThat(client.getBoolean("new-checkout", false)).isTrue();
    }

    @Test
    void a_polling_interval_below_the_minimum_is_ignored_without_a_warning() {
      offlineClient(
          connection ->
              connection.values(Map.of("new-checkout", true)).pollingInterval(Duration.ofSeconds(1)));

      client.initialize();

      assertThat(client.getBoolean("new-checkout", false)).isTrue();
      assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }
  }
}
