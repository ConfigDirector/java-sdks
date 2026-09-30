package com.configdirector.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigType;
import com.configdirector.ConfigsUpdatedEvent;
import com.configdirector.Context;
import com.configdirector.EvaluationReason;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TestClientConformanceTest {

  private static final Duration PROMPTLY = Duration.ofSeconds(5);
  private static final Duration LONGER_THAN_THE_TEST_WAITS = Duration.ofSeconds(30);

  private final List<TestClient> testClients = new ArrayList<>();
  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> appender;
  private Set<Thread> sdkThreadsBefore;

  @BeforeEach
  void setUp() {
    logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("test-client-under-test");
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    sdkThreadsBefore = sdkThreads();
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    for (TestClient testClient : testClients) {
      testClient.close();
    }
  }

  private TestClient create(Map<String, ?> values) {
    return create(values, options -> {});
  }

  private TestClient create(Map<String, ?> values, Consumer<TestClientOptions> configure) {
    TestClient testClient =
        ConfigDirectorTesting.createTestClient(
            values,
            options -> {
              options.logger(logger);
              configure.accept(options);
            });
    testClients.add(testClient);
    return testClient;
  }

  private static Set<Thread> sdkThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().startsWith("configdirector-"))
        .collect(Collectors.toSet());
  }

  private List<String> errors() {
    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.ERROR)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private static List<ConfigsUpdatedEvent> updatesOf(ConfigDirectorClient client) {
    List<ConfigsUpdatedEvent> updates = new CopyOnWriteArrayList<>();
    client.onConfigsUpdated(updates::add);
    return updates;
  }

  private static List<ConfigEvaluation> evaluationsOf(ConfigDirectorClient client) {
    List<ConfigEvaluation> evaluations = new CopyOnWriteArrayList<>();
    client.onConfigEvaluated(event -> evaluations.add(event.evaluation()));
    return evaluations;
  }

  private static AtomicInteger readyCountOf(ConfigDirectorClient client) {
    AtomicInteger count = new AtomicInteger();
    client.onClientReady(event -> count.incrementAndGet());
    return count;
  }

  private static <T> T last(List<T> items) {
    return items.get(items.size() - 1);
  }

  private static Thread startInitializing(ConfigDirectorClient client) {
    Thread thread = new Thread(client::initialize, "initializing-under-test");
    thread.start();
    return thread;
  }

  private static void awaitHeld(Thread initializing) {
    await().atMost(PROMPTLY).until(() -> initializing.getState() == Thread.State.TIMED_WAITING);
  }

  private static void join(Thread thread) throws InterruptedException {
    thread.join(PROMPTLY.toMillis());
    assertThat(thread.isAlive()).isFalse();
  }

  @Test
  void s1_reads_every_seeded_value_type_after_initialize() {
    Map<String, Object> theme = new LinkedHashMap<>();
    theme.put("color", "blue");
    theme.put("sizes", List.of(1, 2));
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("new-checkout", true);
    values.put("max-items", 20);
    values.put("ratio", 2.5);
    values.put("greeting", "hello");
    values.put("theme", theme);
    values.put("tags", List.of("a", "b"));
    ConfigDirectorClient client = create(values).client();

    client.initialize();

    assertThat(client.isReady()).isTrue();
    assertThat(client.getBoolean("new-checkout", false)).isTrue();
    assertThat(client.getInteger("max-items", 0)).isEqualTo(20);
    assertThat(client.getDouble("ratio", 0.0)).isEqualTo(2.5);
    assertThat(client.getString("greeting", "x")).isEqualTo("hello");
    assertThat(client.getJsonObject("theme", Map.of()))
        .containsEntry("color", "blue")
        .containsEntry("sizes", List.of(1L, 2L));
    assertThat(client.getJsonArray("tags", List.of())).containsExactly("a", "b");
  }

  @Test
  void serves_the_same_value_to_every_context_and_lists_every_config_in_get_all_configs() {
    ConfigDirectorClient client = create(Map.of("new-checkout", true, "max-items", 20)).client();
    client.initialize();

    assertThat(client.getBoolean("new-checkout", false, Context.builder().id("user-a").build())).isTrue();
    assertThat(
            client.getBoolean(
                "new-checkout",
                false,
                Context.builder().id("user-b").trait("plan", "pro").build()))
        .isTrue();
    assertThat(client.getAllConfigs()).containsOnlyKeys("new-checkout", "max-items");
    assertThat(client.getAllConfigs().get("new-checkout").type()).isEqualTo(ConfigType.BOOLEAN);
    assertThat(client.getAllConfigs().get("new-checkout").value()).isEqualTo("true");
    assertThat(client.getAllConfigs().get("max-items").type()).isEqualTo(ConfigType.INTEGER);
    assertThat(client.getAllConfigs().get("max-items").value()).isEqualTo("20");
  }

  @Test
  void s2_reading_a_boolean_as_a_string_returns_the_in_code_default_with_type_mismatch() {
    TestClient testClient = create(Map.of("new-checkout", true));
    List<ConfigEvaluation> evaluations = evaluationsOf(testClient.client());
    testClient.client().initialize();

    assertThat(testClient.client().getString("new-checkout", "fallback")).isEqualTo("fallback");

    assertThat(last(evaluations).reason()).isEqualTo(EvaluationReason.TYPE_MISMATCH);
    assertThat(last(evaluations).isDefault()).isTrue();
  }

  @Test
  void s3_set_value_on_a_connected_client_changes_the_next_read() {
    TestClient testClient = create(Map.of("new-checkout", true));
    testClient.client().initialize();

    testClient.setValue("new-checkout", false);

    assertThat(testClient.client().getBoolean("new-checkout", true)).isFalse();
  }

  @Test
  void s4_set_value_fires_the_keys_watch_and_lists_the_key_in_configs_updated() {
    TestClient testClient = create(Map.of("max-items", 20));
    List<Integer> watched = new CopyOnWriteArrayList<>();
    testClient.client().watchInteger("max-items", 0, watched::add);
    List<ConfigsUpdatedEvent> updates = updatesOf(testClient.client());
    testClient.client().initialize();

    testClient.setValue("max-items", 25);

    assertThat(watched).containsExactly(20, 25);
    assertThat(last(updates)).isEqualTo(new ConfigsUpdatedEvent(List.of("max-items"), List.of()));
  }

  @Test
  void s5_set_value_of_another_key_does_not_fire_an_unrelated_watch() {
    TestClient testClient = create(Map.of("a", 1, "b", 2));
    List<Integer> watched = new CopyOnWriteArrayList<>();
    testClient.client().watchInteger("a", 0, watched::add);
    testClient.client().initialize();
    watched.clear();

    testClient.setValue("b", 3);

    assertThat(watched).isEmpty();
  }

  @Test
  void s6_remove_value_makes_reads_return_the_in_code_default_with_config_state_missing() {
    TestClient testClient = create(Map.of("new-checkout", true));
    List<ConfigEvaluation> evaluations = evaluationsOf(testClient.client());
    testClient.client().initialize();

    testClient.removeValue("new-checkout");

    assertThat(testClient.client().getBoolean("new-checkout", false)).isFalse();
    assertThat(last(evaluations).reason()).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING);
  }

  @Test
  void s7_remove_value_fires_the_watch_with_the_default_and_lists_the_key_in_removed_keys() {
    TestClient testClient = create(Map.of("new-checkout", true, "max-items", 20));
    List<Boolean> watched = new CopyOnWriteArrayList<>();
    testClient.client().watchBoolean("new-checkout", false, watched::add);
    List<ConfigsUpdatedEvent> updates = updatesOf(testClient.client());
    testClient.client().initialize();

    testClient.removeValue("new-checkout");

    assertThat(watched).containsExactly(true, false);
    assertThat(last(updates)).isEqualTo(new ConfigsUpdatedEvent(List.of("max-items"), List.of("new-checkout")));
  }

  @Test
  void s8_a_value_set_before_initialize_is_delivered_by_the_first_attempt() {
    TestClient testClient = create(Map.of("new-checkout", true));

    testClient.setValue("max-items", 20);
    testClient.setJsonValue("theme", "{\"color\": \"blue\"}");
    testClient.client().initialize();

    assertThat(testClient.client().getInteger("max-items", 0)).isEqualTo(20);
    assertThat(testClient.client().getJsonObject("theme", Map.of())).containsEntry("color", "blue");
  }

  @Test
  void s9_a_held_initialize_completes_ready_when_completed() throws InterruptedException {
    TestClient testClient = create(Map.of("new-checkout", true), options -> options.timeout(LONGER_THAN_THE_TEST_WAITS));
    ConfigDirectorClient client = testClient.client();
    AtomicInteger ready = readyCountOf(client);
    testClient.holdInitialization();
    Thread initializing = startInitializing(client);
    awaitHeld(initializing);
    assertThat(client.isReady()).isFalse();

    testClient.completeInitialization();

    assertThat(client.isReady()).isTrue();
    assertThat(ready.get()).isEqualTo(1);
    join(initializing);
  }

  @Test
  void s10_a_value_set_while_held_is_delivered_on_completion() throws InterruptedException {
    TestClient testClient = create(Map.of("new-checkout", true), options -> options.timeout(LONGER_THAN_THE_TEST_WAITS));
    ConfigDirectorClient client = testClient.client();
    testClient.holdInitialization();
    Thread initializing = startInitializing(client);
    awaitHeld(initializing);

    testClient.setValue("new-checkout", false);
    testClient.completeInitialization();

    assertThat(client.getBoolean("new-checkout", true)).isFalse();
    join(initializing);
  }

  @Test
  void s11_a_held_initialize_that_times_out_completes_not_ready_and_the_next_one_is_ready() {
    TestClient testClient = create(Map.of("new-checkout", true), options -> options.timeout(Duration.ofMillis(200)));
    ConfigDirectorClient client = testClient.client();
    testClient.holdInitialization();

    long started = System.nanoTime();
    client.initialize();

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(client.isReady()).isFalse();
    testClient.completeInitialization();
    assertThat(client.isReady()).isFalse();

    client.initialize();

    assertThat(client.isReady()).isTrue();
    assertThat(client.getBoolean("new-checkout", false)).isTrue();
  }

  @Test
  void s12_a_failed_initialize_completes_promptly_not_ready_without_client_ready_and_logs_the_error() {
    TestClient testClient = create(Map.of("new-checkout", true));
    ConfigDirectorClient client = testClient.client();
    AtomicInteger ready = readyCountOf(client);
    testClient.failInitialization();

    long started = System.nanoTime();
    client.initialize();

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(client.isReady()).isFalse();
    assertThat(ready.get()).isEqualTo(0);
    assertThat(errors())
        .anySatisfy(error -> assertThat(error).contains("Connection failed with status: 401").contains("unrecoverable"));
  }

  @Test
  void s14_after_close_the_controls_are_silent_no_ops() {
    TestClient testClient = create(Map.of("new-checkout", true));
    List<Boolean> watched = new CopyOnWriteArrayList<>();
    testClient.client().watchBoolean("new-checkout", false, watched::add);
    testClient.client().initialize();

    testClient.close();

    assertThatNoException()
        .isThrownBy(
            () -> {
              testClient.setValue("new-checkout", false);
              testClient.removeValue("new-checkout");
              testClient.replaceValues(Map.of("new-checkout", false));
              testClient.holdInitialization();
              testClient.completeInitialization();
              testClient.failInitialization();
            });
    assertThat(watched).containsExactly(true);
    assertThat(testClient.client().isClosed()).isTrue();
  }

  @Test
  void s15_two_test_clients_in_one_test_never_share_values() {
    TestClient first = create(Map.of("new-checkout", true));
    TestClient second = create(Map.of("new-checkout", false));
    first.client().initialize();
    second.client().initialize();

    second.setValue("new-checkout", true);
    second.setValue("max-items", 1);

    assertThat(first.client().getBoolean("new-checkout", false)).isTrue();
    assertThat(first.client().getInteger("max-items", 0)).isEqualTo(0);
    assertThat(second.client().getInteger("max-items", 0)).isEqualTo(1);
  }

  @Test
  void s17_after_close_no_sdk_thread_is_left_running() {
    TestClient testClient = create(Map.of("new-checkout", true));
    testClient.client().initialize();
    testClient.setValue("new-checkout", false);

    testClient.close();

    assertThat(sdkThreads()).isSubsetOf(sdkThreadsBefore);
  }

  @Test
  void s23_a_full_scenario_starts_no_transport_or_telemetry_thread() {
    TestClient testClient = create(Map.of("new-checkout", true, "max-items", 20));
    testClient.client().initialize();
    testClient.client().getBoolean("new-checkout", false);
    testClient.client().getInteger("max-items", 0);
    testClient.client().getString("new-checkout", "fallback");
    testClient.setValue("max-items", 25);

    assertThat(sdkThreads()).isSubsetOf(sdkThreadsBefore);
    testClient.close();
    assertThat(sdkThreads()).isSubsetOf(sdkThreadsBefore);
  }

  @Test
  void s24_a_value_removed_before_initialize_reads_as_the_in_code_default_with_config_state_missing() {
    TestClient testClient = create(Map.of("new-checkout", true));
    List<ConfigEvaluation> evaluations = evaluationsOf(testClient.client());

    testClient.removeValue("new-checkout");
    testClient.client().initialize();

    assertThat(testClient.client().isReady()).isTrue();
    assertThat(testClient.client().getBoolean("new-checkout", false)).isFalse();
    assertThat(last(evaluations).reason()).isEqualTo(EvaluationReason.CONFIG_STATE_MISSING);
  }

  @Test
  void s25_the_attempt_after_a_failure_succeeds_with_the_values_stored_in_the_meantime() {
    TestClient testClient = create(Map.of("new-checkout", true));
    testClient.failInitialization();
    testClient.client().initialize();

    testClient.setValue("max-items", 4);
    testClient.client().initialize();

    assertThat(testClient.client().isReady()).isTrue();
    assertThat(testClient.client().getInteger("max-items", 0)).isEqualTo(4);
  }

  @Test
  void s32_replace_values_serves_exactly_the_new_values_and_fires_the_watch_of_a_dropped_key() {
    TestClient testClient = create(Map.of("a", 1, "b", 2));
    List<Integer> watchedB = new CopyOnWriteArrayList<>();
    testClient.client().watchInteger("b", 0, watchedB::add);
    testClient.client().initialize();

    testClient.replaceValues(Map.of("a", 10, "c", 3));

    assertThat(testClient.client().getInteger("a", 0)).isEqualTo(10);
    assertThat(testClient.client().getInteger("b", 0)).isEqualTo(0);
    assertThat(testClient.client().getInteger("c", 0)).isEqualTo(3);
    assertThat(watchedB).containsExactly(2, 0);
  }

  @Test
  void s33_closing_the_client_ends_a_held_initialize_promptly_not_ready() throws InterruptedException {
    TestClient testClient = create(Map.of("new-checkout", true), options -> options.timeout(LONGER_THAN_THE_TEST_WAITS));
    ConfigDirectorClient client = testClient.client();
    testClient.holdInitialization();
    Thread initializing = startInitializing(client);
    awaitHeld(initializing);

    testClient.close();

    join(initializing);
    assertThat(client.isReady()).isFalse();
    assertThat(sdkThreads()).isSubsetOf(sdkThreadsBefore);
  }

  @Test
  void s38_completing_before_initialize_picks_the_hold_up_lets_it_proceed_at_once() {
    TestClient testClient = create(Map.of("new-checkout", true));
    testClient.holdInitialization();
    testClient.completeInitialization();

    long started = System.nanoTime();
    testClient.client().initialize();

    assertThat(testClient.client().isReady()).isTrue();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void s40_replace_values_disarms_an_armed_hold() {
    TestClient testClient = create(Map.of("new-checkout", true));
    testClient.holdInitialization();

    testClient.replaceValues(Map.of("max-items", 5));
    long started = System.nanoTime();
    testClient.client().initialize();

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(testClient.client().isReady()).isTrue();
    assertThat(testClient.client().getInteger("max-items", 0)).isEqualTo(5);
    assertThat(testClient.client().getBoolean("new-checkout", false)).isFalse();
  }

  @Test
  void s41_a_set_value_from_a_watch_is_delivered_after_the_outer_delivery() {
    TestClient testClient = create(Map.of("a", 1, "b", 1));
    ConfigDirectorClient client = testClient.client();
    List<String> order = new CopyOnWriteArrayList<>();
    AtomicInteger ready = readyCountOf(client);
    client.watchInteger(
        "a",
        0,
        value -> {
          if (value == 2) {
            testClient.setValue("b", 2);
            order.add("a-watch-returned");
          }
        });
    client.watchInteger("b", 0, value -> order.add("b=" + value));
    client.initialize();
    order.clear();

    testClient.setValue("a", 2);

    assertThat(order).containsExactly("a-watch-returned", "b=2");
    assertThat(ready.get()).isEqualTo(1);
    assertThat(client.getInteger("a", 0)).isEqualTo(2);
    assertThat(client.getInteger("b", 0)).isEqualTo(2);
  }

  @Test
  void a_string_is_always_a_string_config_and_json_text_is_a_json_config() {
    TestClient testClient = create(Map.of("as-text", "{\"a\":1}"));
    testClient.setJsonValue("as-json", "{\"a\":1}");
    testClient.client().initialize();

    assertThat(testClient.client().getAllConfigs().get("as-text").type()).isEqualTo(ConfigType.STRING);
    assertThat(testClient.client().getAllConfigs().get("as-json").type()).isEqualTo(ConfigType.JSON);
    assertThat(testClient.client().getString("as-text", "x")).isEqualTo("{\"a\":1}");
    assertThat(testClient.client().getJsonObject("as-json", Map.of())).containsEntry("a", 1L);
  }

  @Test
  void rejected_values_throw_the_sdks_validation_exception_and_change_nothing() {
    TestClient testClient = create(Map.of("new-checkout", true));
    testClient.client().initialize();

    assertThatExceptionOfType(ConfigDirectorValidationException.class)
        .isThrownBy(() -> testClient.setValue("new-checkout", null));
    assertThatExceptionOfType(ConfigDirectorValidationException.class)
        .isThrownBy(() -> testClient.setValue("new-checkout", new BigDecimal("1.5")));
    assertThatExceptionOfType(ConfigDirectorValidationException.class)
        .isThrownBy(() -> testClient.setValue("new-checkout", Double.NaN));
    assertThatExceptionOfType(ConfigDirectorValidationException.class)
        .isThrownBy(() -> testClient.setValue(" ", true));
    assertThatExceptionOfType(ConfigDirectorValidationException.class)
        .isThrownBy(() -> testClient.setJsonValue("theme", "{color: blue}"));
    assertThatExceptionOfType(ConfigDirectorValidationException.class)
        .isThrownBy(() -> testClient.setJsonValue("theme", "42"));
    assertThatExceptionOfType(ConfigDirectorValidationException.class)
        .isThrownBy(() -> testClient.replaceValues(Map.of("new-checkout", new Object())));
    assertThatExceptionOfType(ConfigDirectorValidationException.class)
        .isThrownBy(() -> ConfigDirectorTesting.createTestClient(Map.of("new-checkout", new Object())));

    assertThat(testClient.client().getBoolean("new-checkout", false)).isTrue();
  }

  @Test
  void an_empty_string_serves_the_in_code_default_with_value_missing() {
    TestClient testClient = create(Map.of("greeting", ""));
    List<ConfigEvaluation> evaluations = evaluationsOf(testClient.client());
    testClient.client().initialize();

    assertThat(testClient.client().getString("greeting", "fallback")).isEqualTo("fallback");
    assertThat(last(evaluations).reason()).isEqualTo(EvaluationReason.VALUE_MISSING);
  }

  @Test
  void the_default_logger_is_the_sdks_logger() {
    ch.qos.logback.classic.Logger sdkLogger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(com.configdirector.ConfigDirector.LOGGER_NAME);
    ListAppender<ILoggingEvent> sdkAppender = new ListAppender<>();
    sdkAppender.start();
    sdkLogger.addAppender(sdkAppender);
    try (TestClient testClient = ConfigDirectorTesting.createTestClient(Map.of("new-checkout", true))) {
      testClient.failInitialization();
      testClient.client().initialize();

      assertThat(sdkAppender.list)
          .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("Connection failed with status: 401"));
    } finally {
      sdkLogger.detachAppender(sdkAppender);
    }
  }
}
