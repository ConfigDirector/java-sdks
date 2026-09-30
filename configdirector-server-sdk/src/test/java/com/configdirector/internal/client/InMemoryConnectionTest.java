package com.configdirector.internal.client;

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
import com.configdirector.ConfigState;
import com.configdirector.ConfigType;
import com.configdirector.ConfigsUpdatedEvent;
import com.configdirector.EvaluationReason;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class InMemoryConnectionTest {

  private static final Duration PROMPTLY = Duration.ofSeconds(5);
  private static final Duration LONGER_THAN_THE_TEST_WAITS = Duration.ofSeconds(30);

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> appender;
  private final List<InMemoryConnection> connections = new ArrayList<>();
  private Set<Thread> sdkThreadsBefore;

  @BeforeEach
  void setUp() {
    logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("in-memory-connection-under-test");
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    sdkThreadsBefore = sdkThreads();
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    for (InMemoryConnection connection : connections) {
      connection.client().close(Duration.ZERO);
    }
  }

  private InMemoryConnection connection(Map<String, ?> values) {
    return connection(values, null);
  }

  private InMemoryConnection connection(Map<String, ?> values, Duration timeout) {
    InMemoryConnection connection = new InMemoryConnection(values, timeout, logger);
    connections.add(connection);
    return connection;
  }

  private static Set<Thread> sdkThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().startsWith("configdirector-"))
        .collect(Collectors.toSet());
  }

  private List<ILoggingEvent> errors() {
    return appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
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

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static ConfigState stateOf(ConfigDirectorClient client, String key) {
    return client.getAllConfigs().get(key);
  }

  @Nested
  @DisplayName("encoding values")
  class Encoding {

    @Test
    void each_native_type_becomes_the_matching_config_type() {
      Map<String, Object> theme = new LinkedHashMap<>();
      theme.put("z", 1);
      theme.put("a", List.of(1, 2.5, "x"));
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("flag", true);
      values.put("count", 20);
      values.put("big", 3_000_000_000L);
      values.put("ratio", 2.5);
      values.put("share", 0.1f);
      values.put("greeting", "hello");
      values.put("theme", theme);
      values.put("tags", List.of("a", "b"));
      ConfigDirectorClient client = connection(values).client();
      client.initialize();

      assertThat(stateOf(client, "flag").type()).isEqualTo(ConfigType.BOOLEAN);
      assertThat(stateOf(client, "flag").value()).isEqualTo("true");
      assertThat(stateOf(client, "count").type()).isEqualTo(ConfigType.INTEGER);
      assertThat(stateOf(client, "count").value()).isEqualTo("20");
      assertThat(stateOf(client, "big").type()).isEqualTo(ConfigType.INTEGER);
      assertThat(stateOf(client, "big").value()).isEqualTo("3000000000");
      assertThat(stateOf(client, "ratio").type()).isEqualTo(ConfigType.FLOAT);
      assertThat(stateOf(client, "ratio").value()).isEqualTo("2.5");
      assertThat(stateOf(client, "share").type()).isEqualTo(ConfigType.FLOAT);
      assertThat(stateOf(client, "share").value()).isEqualTo("0.1");
      assertThat(stateOf(client, "greeting").type()).isEqualTo(ConfigType.STRING);
      assertThat(stateOf(client, "greeting").value()).isEqualTo("hello");
      assertThat(stateOf(client, "theme").type()).isEqualTo(ConfigType.JSON);
      assertThat(stateOf(client, "theme").value()).isEqualTo("{\"z\":1,\"a\":[1,2.5,\"x\"]}");
      assertThat(stateOf(client, "tags").type()).isEqualTo(ConfigType.JSON);
      assertThat(stateOf(client, "tags").value()).isEqualTo("[\"a\",\"b\"]");

      assertThat(client.getBoolean("flag", false)).isTrue();
      assertThat(client.getInteger("count", 0)).isEqualTo(20);
      assertThat(client.getValue("big", 0L)).isEqualTo(3_000_000_000L);
      assertThat(client.getDouble("ratio", 0.0)).isEqualTo(2.5);
      assertThat(client.getValue("share", 0.0f)).isEqualTo(0.1f);
      assertThat(client.getString("greeting", "x")).isEqualTo("hello");
      assertThat(client.getJsonObject("theme", Map.of()))
          .containsEntry("z", 1L)
          .containsEntry("a", List.of(1L, 2.5, "x"));
      assertThat(client.getJsonArray("tags", List.of())).containsExactly("a", "b");
    }

    @Test
    void floats_are_written_in_plain_notation_without_an_exponent() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("tiny", 1e-7);
      values.put("huge", 1e21);
      values.put("tiny-float", 1e-7f);
      values.put("whole", 2.0);
      ConfigDirectorClient client = connection(values).client();
      client.initialize();

      assertThat(stateOf(client, "tiny").value()).isEqualTo("0.0000001");
      assertThat(stateOf(client, "huge").value()).isEqualTo("1000000000000000000000");
      assertThat(stateOf(client, "tiny-float").value()).isEqualTo("0.0000001");
      assertThat(stateOf(client, "whole").value()).isEqualTo("2.0");
      assertThat(client.getDouble("tiny", 0.0)).isEqualTo(1e-7);
      assertThat(client.getDouble("huge", 0.0)).isEqualTo(1e21);
      assertThat(client.getValue("tiny-float", 0.0f)).isEqualTo(1e-7f);
    }

    @Test
    void maps_keep_a_defined_order_and_are_sorted_otherwise() {
      Map<String, Object> insertion = new LinkedHashMap<>();
      insertion.put("b", 1);
      insertion.put("a", 2);
      Map<String, Object> reversed = new TreeMap<>(Comparator.reverseOrder());
      reversed.put("a", 1);
      reversed.put("b", 2);
      Map<String, Object> hashed = new HashMap<>();
      hashed.put("b", 1);
      hashed.put("a", 2);
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("insertion", insertion);
      values.put("reversed", reversed);
      values.put("hashed", hashed);
      values.put("immutable", Map.of("b", 1, "a", 2));
      values.put("nested", List.of(Map.of("b", 1, "a", 2)));
      ConfigDirectorClient client = connection(values).client();
      client.initialize();

      assertThat(stateOf(client, "insertion").value()).isEqualTo("{\"b\":1,\"a\":2}");
      assertThat(stateOf(client, "reversed").value()).isEqualTo("{\"b\":2,\"a\":1}");
      assertThat(stateOf(client, "hashed").value()).isEqualTo("{\"a\":2,\"b\":1}");
      assertThat(stateOf(client, "immutable").value()).isEqualTo("{\"a\":2,\"b\":1}");
      assertThat(stateOf(client, "nested").value()).isEqualTo("[{\"a\":2,\"b\":1}]");
    }

    @Test
    void json_contents_may_hold_null_booleans_numbers_strings_maps_and_lists() {
      Map<String, Object> contents = new LinkedHashMap<>();
      contents.put("none", null);
      contents.put("flag", false);
      contents.put("count", 7L);
      contents.put("share", 0.5f);
      contents.put("text", "<a>&");
      contents.put("list", List.of(List.of(1)));
      ConfigDirectorClient client = connection(Map.of("theme", contents)).client();
      client.initialize();

      assertThat(stateOf(client, "theme").value())
          .isEqualTo(
              "{\"none\":null,\"flag\":false,\"count\":7,\"share\":0.5,\"text\":\"<a>&\",\"list\":[[1]]}");
    }

    @Test
    void a_string_is_always_a_string_config() {
      ConfigDirectorClient client = connection(Map.of("theme", "{\"a\":1}")).client();
      client.initialize();

      assertThat(stateOf(client, "theme").type()).isEqualTo(ConfigType.STRING);
      assertThat(client.getString("theme", "x")).isEqualTo("{\"a\":1}");
    }

    @Test
    void json_text_is_parsed_strictly_and_written_compactly() {
      InMemoryConnection connection = connection(Map.of());
      connection.setJsonValue("theme", " {\"z\": 1, \"a\": [true, null, \"x\"]} ");
      connection.setJsonValue("tags", "[1, 2.5]");
      connection.client().initialize();

      assertThat(stateOf(connection.client(), "theme").type()).isEqualTo(ConfigType.JSON);
      assertThat(stateOf(connection.client(), "theme").value())
          .isEqualTo("{\"z\":1,\"a\":[true,null,\"x\"]}");
      assertThat(stateOf(connection.client(), "tags").value()).isEqualTo("[1,2.5]");
      assertThat(connection.client().getJsonObject("theme", Map.of())).containsEntry("z", 1L);
    }

    @Test
    void json_text_that_is_not_strict_json_or_not_a_document_is_rejected() {
      InMemoryConnection connection = connection(Map.of());

      for (String text :
          List.of("{a:1}", "{'a':1}", "{\"a\":1,}", "[NaN]", "[1] [2]", "[1] x", "42", "\"x\"", "true", "")) {
        assertThatExceptionOfType(ConfigDirectorValidationException.class)
            .as(text)
            .isThrownBy(() -> connection.setJsonValue("theme", text))
            .withMessageContaining("theme");
      }
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setJsonValue("theme", "[1] [2]"))
          .withMessageContaining("continues after the JSON value");
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setJsonValue("theme", null));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setJsonValue(" ", "[1]"));
    }

    @Test
    void unsupported_values_are_rejected_and_leave_the_stored_values_unchanged() {
      Map<String, Object> withNull = new HashMap<>();
      withNull.put("flag", null);
      Map<Object, Object> nonStringKeys = new HashMap<>();
      nonStringKeys.put(1, "one");
      InMemoryConnection connection = connection(Map.of("flag", true));
      connection.client().initialize();

      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setValue("flag", null))
          .withMessageContaining("flag");
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setValue("flag", new BigDecimal("1.5")))
          .withMessageContaining("BigDecimal");
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setValue("flag", new Object()));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setValue("flag", Double.NaN));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setValue("flag", Float.POSITIVE_INFINITY));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setValue("flag", Map.of("a", new BigDecimal("1"))))
          .withMessageContaining("a");
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setValue("flag", List.of(1, Double.NaN)));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setValue("flag", nonStringKeys));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setValue(" ", true));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.setValue(null, true));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection.replaceValues(withNull));
      assertThatExceptionOfType(ConfigDirectorValidationException.class)
          .isThrownBy(() -> connection(withNull));

      assertThat(connection.client().getBoolean("flag", false)).isTrue();
    }
  }

  @Nested
  @DisplayName("the default mode")
  class DefaultMode {

    @Test
    void initialize_completes_ready_with_the_seeded_values_and_fires_client_ready() {
      ConfigDirectorClient client = connection(Map.of("flag", true, "count", 20)).client();
      AtomicInteger ready = readyCountOf(client);
      List<ConfigsUpdatedEvent> updates = updatesOf(client);

      client.initialize();

      assertThat(client.isReady()).isTrue();
      assertThat(ready.get()).isEqualTo(1);
      assertThat(updates).containsExactly(new ConfigsUpdatedEvent(List.of("count", "flag")));
      assertThat(client.getBoolean("flag", false)).isTrue();
      assertThat(client.getInteger("count", 0)).isEqualTo(20);
    }

    @Test
    void the_same_value_is_served_to_every_context() {
      ConfigDirectorClient client = connection(Map.of("flag", true)).client();
      client.initialize();

      assertThat(
              client.getBoolean(
                  "flag", false, com.configdirector.Context.builder().id("user-a").build()))
          .isTrue();
      assertThat(
              client.getBoolean(
                  "flag",
                  false,
                  com.configdirector.Context.builder().id("user-b").trait("plan", "pro").build()))
          .isTrue();
    }

    @Test
    void a_boolean_read_as_a_string_returns_the_default_with_type_mismatch() {
      ConfigDirectorClient client = connection(Map.of("flag", true)).client();
      List<ConfigEvaluation> evaluations = evaluationsOf(client);
      client.initialize();

      assertThat(client.getString("flag", "fallback")).isEqualTo("fallback");
      assertThat(evaluations.get(evaluations.size() - 1).reason())
          .isEqualTo(EvaluationReason.TYPE_MISMATCH);
    }

    @Test
    void set_value_on_a_connected_client_delivers_a_delta_carrying_only_that_key() {
      InMemoryConnection connection = connection(Map.of("count", 20, "other", 1));
      ConfigDirectorClient client = connection.client();
      List<Integer> watchedCount = new CopyOnWriteArrayList<>();
      List<Integer> watchedOther = new CopyOnWriteArrayList<>();
      client.watchInteger("count", 0, watchedCount::add);
      client.watchInteger("other", 0, watchedOther::add);
      List<ConfigsUpdatedEvent> updates = updatesOf(client);
      client.initialize();

      connection.setValue("count", 25);

      assertThat(client.getInteger("count", 0)).isEqualTo(25);
      assertThat(watchedCount).containsExactly(20, 25);
      assertThat(watchedOther).containsExactly(1);
      assertThat(updates.get(updates.size() - 1))
          .isEqualTo(new ConfigsUpdatedEvent(List.of("count"), List.of()));
    }

    @Test
    void remove_value_delivers_a_full_update_without_the_key() {
      InMemoryConnection connection = connection(Map.of("flag", true, "count", 20));
      ConfigDirectorClient client = connection.client();
      List<Boolean> watched = new CopyOnWriteArrayList<>();
      client.watchBoolean("flag", false, watched::add);
      List<ConfigsUpdatedEvent> updates = updatesOf(client);
      List<ConfigEvaluation> evaluations = evaluationsOf(client);
      client.initialize();

      connection.removeValue("flag");

      assertThat(client.getBoolean("flag", false)).isFalse();
      assertThat(evaluations.get(evaluations.size() - 1).reason())
          .isEqualTo(EvaluationReason.CONFIG_STATE_MISSING);
      assertThat(watched).containsExactly(true, false);
      assertThat(updates.get(updates.size() - 1))
          .isEqualTo(new ConfigsUpdatedEvent(List.of("count"), List.of("flag")));
    }

    @Test
    void replace_values_delivers_exactly_the_new_values() {
      InMemoryConnection connection = connection(Map.of("a", 1, "b", 2));
      ConfigDirectorClient client = connection.client();
      List<Integer> watchedB = new CopyOnWriteArrayList<>();
      client.watchInteger("b", 0, watchedB::add);
      List<ConfigsUpdatedEvent> updates = updatesOf(client);
      client.initialize();

      connection.replaceValues(Map.of("a", 10, "c", 3));

      assertThat(client.getInteger("a", 0)).isEqualTo(10);
      assertThat(client.getInteger("b", 0)).isEqualTo(0);
      assertThat(client.getInteger("c", 0)).isEqualTo(3);
      assertThat(watchedB).containsExactly(2, 0);
      assertThat(updates.get(updates.size() - 1))
          .isEqualTo(new ConfigsUpdatedEvent(List.of("a", "c"), List.of("b")));
    }

    @Test
    void changes_before_initialize_are_delivered_by_the_first_attempt() {
      InMemoryConnection connection = connection(Map.of("flag", true, "count", 20));
      ConfigDirectorClient client = connection.client();
      List<ConfigsUpdatedEvent> updates = updatesOf(client);

      connection.setValue("greeting", "hello");
      connection.removeValue("count");
      client.initialize();

      assertThat(updates).containsExactly(new ConfigsUpdatedEvent(List.of("flag", "greeting")));
      assertThat(client.getString("greeting", "x")).isEqualTo("hello");
      assertThat(client.getInteger("count", 7)).isEqualTo(7);
    }

    @Test
    void an_operation_called_from_a_watcher_is_delivered_after_the_outer_delivery() {
      InMemoryConnection connection = connection(Map.of("a", 1, "b", 1));
      ConfigDirectorClient client = connection.client();
      List<ConfigsUpdatedEvent> updates = updatesOf(client);
      List<String> order = new CopyOnWriteArrayList<>();
      AtomicInteger ready = readyCountOf(client);
      client.watchInteger(
          "a",
          0,
          value -> {
            if (value == 2) {
              connection.setValue("b", 2);
              order.add("a-watcher-returned");
            }
          });
      client.watchInteger("b", 0, value -> order.add("b=" + value));
      client.initialize();
      order.clear();

      connection.setValue("a", 2);

      assertThat(order).containsExactly("a-watcher-returned", "b=2");
      assertThat(updates.subList(1, updates.size()))
          .containsExactly(
              new ConfigsUpdatedEvent(List.of("a")), new ConfigsUpdatedEvent(List.of("b")));
      assertThat(ready.get()).isEqualTo(1);
      assertThat(client.getInteger("a", 0)).isEqualTo(2);
      assertThat(client.getInteger("b", 0)).isEqualTo(2);
    }

    @Test
    void a_delivery_requested_while_another_thread_delivers_is_made_by_that_thread()
        throws InterruptedException {
      InMemoryConnection connection = connection(Map.of("a", 1, "b", 1));
      ConfigDirectorClient client = connection.client();
      CountDownLatch watcherEntered = new CountDownLatch(1);
      CountDownLatch releaseWatcher = new CountDownLatch(1);
      List<String> events = new CopyOnWriteArrayList<>();
      client.watchInteger(
          "a",
          0,
          value -> {
            if (value == 2) {
              watcherEntered.countDown();
              awaitUninterruptibly(releaseWatcher);
              events.add("a=2 on " + Thread.currentThread().getName());
            }
          });
      client.watchInteger("b", 0, value -> events.add("b=" + value + " on " + Thread.currentThread().getName()));
      client.initialize();
      events.clear();
      Thread outer = new Thread(() -> connection.setValue("a", 2), "outer-delivery");
      outer.start();
      assertThat(watcherEntered.await(PROMPTLY.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

      connection.setValue("b", 2);

      assertThat(events).isEmpty();
      releaseWatcher.countDown();
      join(outer);
      assertThat(events).containsExactly("a=2 on outer-delivery", "b=2 on outer-delivery");
      assertThat(client.getInteger("b", 0)).isEqualTo(2);
    }

    @Test
    void a_watcher_error_abandons_the_queued_deliveries_but_not_later_ones() {
      InMemoryConnection connection = connection(Map.of("a", 1, "b", 1));
      ConfigDirectorClient client = connection.client();
      List<Integer> watchedB = new CopyOnWriteArrayList<>();
      client.watchInteger(
          "a",
          0,
          value -> {
            if (value == 2) {
              connection.setValue("b", 2);
              throw new AssertionError("a watcher assertion failed");
            }
          });
      client.watchInteger("b", 0, watchedB::add);
      client.initialize();

      assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> connection.setValue("a", 2));
      connection.setValue("b", 3);

      assertThat(watchedB).containsExactly(1, 3);
    }

    @Test
    void two_connections_never_share_values() {
      ConfigDirectorClient first = connection(Map.of("flag", true)).client();
      InMemoryConnection second = connection(Map.of("flag", false));
      first.initialize();
      second.client().initialize();

      second.setValue("flag", true);
      second.setValue("count", 1);

      assertThat(first.getBoolean("flag", false)).isTrue();
      assertThat(first.getInteger("count", 0)).isEqualTo(0);
      assertThat(second.client().getInteger("count", 0)).isEqualTo(1);
    }

    @Test
    void after_close_operations_are_silent_and_nothing_the_connection_started_remains() {
      InMemoryConnection connection = connection(Map.of("flag", true));
      ConfigDirectorClient client = connection.client();
      List<Boolean> watched = new CopyOnWriteArrayList<>();
      client.watchBoolean("flag", false, watched::add);
      client.initialize();
      connection.setValue("flag", false);

      client.close();

      assertThatNoException()
          .isThrownBy(
              () -> {
                connection.setValue("flag", true);
                connection.removeValue("flag");
                connection.replaceValues(Map.of("flag", true));
                connection.holdInitialization();
                connection.completeInitialization();
                connection.failInitialization();
              });
      assertThat(watched).containsExactly(true, false);
      assertThat(sdkThreads()).isSubsetOf(sdkThreadsBefore);
    }
  }

  @Nested
  @DisplayName("holding initialization")
  class Holding {

    @Test
    void a_held_initialize_completes_ready_when_completed() throws InterruptedException {
      InMemoryConnection connection = connection(Map.of("flag", true), LONGER_THAN_THE_TEST_WAITS);
      ConfigDirectorClient client = connection.client();
      AtomicInteger ready = readyCountOf(client);
      connection.holdInitialization();

      Thread initializing = startInitializing(client);
      awaitHeld(initializing);
      assertThat(client.isReady()).isFalse();

      connection.completeInitialization();

      assertThat(client.isReady()).isTrue();
      assertThat(ready.get()).isEqualTo(1);
      assertThat(client.getBoolean("flag", false)).isTrue();
      join(initializing);

      connection.setValue("flag", false);

      assertThat(client.getBoolean("flag", true)).isFalse();
    }

    @Test
    void a_value_set_while_held_is_delivered_on_completion() throws InterruptedException {
      InMemoryConnection connection = connection(Map.of("flag", true), LONGER_THAN_THE_TEST_WAITS);
      ConfigDirectorClient client = connection.client();
      connection.holdInitialization();
      Thread initializing = startInitializing(client);
      awaitHeld(initializing);

      connection.setValue("flag", false);
      assertThat(client.isReady()).isFalse();
      connection.completeInitialization();

      assertThat(client.getBoolean("flag", true)).isFalse();
      join(initializing);
    }

    @Test
    void a_held_initialize_that_times_out_completes_not_ready_and_uses_the_hold_up() {
      InMemoryConnection connection = connection(Map.of("flag", true), Duration.ofMillis(200));
      ConfigDirectorClient client = connection.client();
      AtomicInteger ready = readyCountOf(client);
      connection.holdInitialization();

      client.initialize();

      assertThat(client.isReady()).isFalse();
      connection.completeInitialization();
      assertThat(client.isReady()).isFalse();
      assertThat(ready.get()).isEqualTo(0);

      client.initialize();

      assertThat(client.isReady()).isTrue();
      assertThat(client.getBoolean("flag", false)).isTrue();
    }

    @Test
    void completing_before_initialize_picks_the_hold_up_disarms_it() {
      InMemoryConnection connection = connection(Map.of("flag", true));
      connection.holdInitialization();
      connection.completeInitialization();

      long started = System.nanoTime();
      connection.client().initialize();

      assertThat(connection.client().isReady()).isTrue();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void replace_values_disarms_an_armed_hold() {
      InMemoryConnection connection = connection(Map.of("flag", true));
      connection.holdInitialization();

      connection.replaceValues(Map.of("count", 5));
      connection.client().initialize();

      assertThat(connection.client().isReady()).isTrue();
      assertThat(connection.client().getInteger("count", 0)).isEqualTo(5);
      assertThat(connection.client().getBoolean("flag", false)).isFalse();
    }

    @Test
    void closing_the_client_ends_a_held_initialize_promptly() throws InterruptedException {
      InMemoryConnection connection = connection(Map.of("flag", true), LONGER_THAN_THE_TEST_WAITS);
      ConfigDirectorClient client = connection.client();
      connection.holdInitialization();
      Thread initializing = startInitializing(client);
      awaitHeld(initializing);

      client.close();

      join(initializing);
      assertThat(client.isReady()).isFalse();
      assertThat(sdkThreads()).isSubsetOf(sdkThreadsBefore);
    }

    @Test
    void a_new_attempt_ends_the_held_one() throws InterruptedException {
      InMemoryConnection connection = connection(Map.of("flag", true), LONGER_THAN_THE_TEST_WAITS);
      ConfigDirectorClient client = connection.client();
      connection.holdInitialization();
      Thread held = startInitializing(client);
      awaitHeld(held);

      client.initialize();

      assertThat(client.isReady()).isTrue();
      join(held);
    }

    @Test
    void holding_twice_arms_one_hold() {
      InMemoryConnection connection = connection(Map.of("flag", true), Duration.ofMillis(200));
      connection.holdInitialization();
      connection.holdInitialization();

      connection.client().initialize();
      assertThat(connection.client().isReady()).isFalse();
      connection.client().initialize();

      assertThat(connection.client().isReady()).isTrue();
    }
  }

  @Nested
  @DisplayName("failing initialization")
  class Failing {

    @Test
    void an_armed_failure_fails_the_next_initialize_promptly_as_a_fatal_error() {
      InMemoryConnection connection = connection(Map.of("flag", true));
      ConfigDirectorClient client = connection.client();
      AtomicInteger ready = readyCountOf(client);
      connection.failInitialization();

      long started = System.nanoTime();
      client.initialize();

      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
      assertThat(client.isReady()).isFalse();
      assertThat(ready.get()).isEqualTo(0);
      assertThat(errors())
          .anySatisfy(
              event ->
                  assertThat(event.getFormattedMessage())
                      .contains("[InMemoryConnection]")
                      .contains("Connection failed with status: 401")
                      .contains("unrecoverable"));
    }

    @Test
    void failing_a_held_initialize_ends_it_promptly_not_ready() throws InterruptedException {
      InMemoryConnection connection = connection(Map.of("flag", true), LONGER_THAN_THE_TEST_WAITS);
      ConfigDirectorClient client = connection.client();
      connection.holdInitialization();
      Thread initializing = startInitializing(client);
      awaitHeld(initializing);

      connection.failInitialization();

      join(initializing);
      assertThat(client.isReady()).isFalse();
      assertThat(errors())
          .anySatisfy(
              event -> assertThat(event.getFormattedMessage()).contains("[InMemoryConnection]"));
    }

    @Test
    void the_attempt_after_a_failure_succeeds_with_the_stored_values() {
      InMemoryConnection connection = connection(Map.of("flag", true));
      ConfigDirectorClient client = connection.client();
      connection.failInitialization();
      client.initialize();

      connection.setValue("count", 4);
      client.initialize();

      assertThat(client.isReady()).isTrue();
      assertThat(client.getInteger("count", 0)).isEqualTo(4);
    }

    @Test
    void the_last_armed_control_wins() {
      InMemoryConnection connection = connection(Map.of("flag", true), Duration.ofMillis(200));
      connection.holdInitialization();
      connection.failInitialization();
      connection.client().initialize();
      assertThat(errors()).isNotEmpty();
      appender.list.clear();

      connection.failInitialization();
      connection.holdInitialization();
      connection.client().initialize();

      assertThat(errors()).isEmpty();
      assertThat(connection.client().isReady()).isFalse();
    }

    @Test
    void a_failure_disconnects_so_a_later_set_value_only_stores() {
      InMemoryConnection connection = connection(Map.of("flag", true));
      ConfigDirectorClient client = connection.client();
      List<Boolean> watched = new CopyOnWriteArrayList<>();
      client.watchBoolean("flag", false, watched::add);
      connection.failInitialization();
      client.initialize();

      connection.setValue("flag", false);

      assertThat(watched).isEmpty();
    }
  }
}
