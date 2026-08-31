package com.configdirector;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads configs and feature flags from ConfigDirector.
 *
 * <p>Build one with {@link ConfigDirector#client}, call {@link #initialize()} once during
 * application startup, and close it on shutdown. A client is safe to share across threads.
 *
 * <p>Every getter takes a default and returns it rather than throwing: a config the SDK has never
 * heard of, an unreachable server, or a value that will not coerce to the requested type all
 * produce the default. Which of those happened is reported through {@link #onConfigEvaluated}.
 *
 * <p>Each getter comes in two forms, with and without a {@link Context}. The context is what
 * targeting rules are evaluated against, so the same key can resolve differently per user.
 *
 * <p>Watching mirrors that: one watch per type, each in the same two forms. A watch fires whenever
 * an update carries its key, with the value evaluated afresh, and handlers run on the transport
 * thread, so one that blocks delays later updates.
 */
public interface ConfigDirectorClient extends AutoCloseable {

  /** Connects and waits for the first config state, bounded by the configured timeout. */
  void initialize();

  /**
   * Connects and waits for the first config state, bounded by {@code timeout}.
   *
   * @param timeout how long to wait; must be positive
   */
  void initialize(Duration timeout);

  /**
   * Whether config state has arrived. Until it has, every getter returns its default.
   *
   * @return true once the first config state has been received
   */
  boolean isReady();

  /**
   * Whether {@link #close()} has been called. A closed client cannot be reopened.
   *
   * @return true once the client has been closed
   */
  boolean isClosed();

  /**
   * Evaluates {@code configKey} as a boolean.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or not a boolean
   * @return the evaluated value, or {@code defaultValue}
   */
  boolean getBoolean(String configKey, boolean defaultValue);

  /**
   * Evaluates {@code configKey} as a boolean, against {@code context}.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or not a boolean
   * @param context evaluated against targeting rules; may be null
   * @return the evaluated value, or {@code defaultValue}
   */
  boolean getBoolean(String configKey, boolean defaultValue, Context context);

  /**
   * Evaluates {@code configKey} as text.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing or unreachable
   * @return the evaluated value, or {@code defaultValue}
   */
  String getString(String configKey, String defaultValue);

  /**
   * Evaluates {@code configKey} as text, against {@code context}.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing or unreachable
   * @param context evaluated against targeting rules; may be null
   * @return the evaluated value, or {@code defaultValue}
   */
  String getString(String configKey, String defaultValue, Context context);

  /**
   * Evaluates {@code configKey} as a whole number.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or not a whole number
   * @return the evaluated value, or {@code defaultValue}
   */
  int getInteger(String configKey, int defaultValue);

  /**
   * Evaluates {@code configKey} as a whole number, against {@code context}.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or not a whole number
   * @param context evaluated against targeting rules; may be null
   * @return the evaluated value, or {@code defaultValue}
   */
  int getInteger(String configKey, int defaultValue, Context context);

  /**
   * Evaluates {@code configKey} as a number.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or not a number
   * @return the evaluated value, or {@code defaultValue}
   */
  double getDouble(String configKey, double defaultValue);

  /**
   * Evaluates {@code configKey} as a number, against {@code context}.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or not a number
   * @param context evaluated against targeting rules; may be null
   * @return the evaluated value, or {@code defaultValue}
   */
  double getDouble(String configKey, double defaultValue, Context context);

  /**
   * Evaluates {@code configKey} as a JSON object. Values inside are String, Number, Boolean, List,
   * Map, or null.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or not a JSON object
   * @return the evaluated value, or {@code defaultValue}
   */
  Map<String, Object> getJsonObject(String configKey, Map<String, Object> defaultValue);

  /**
   * Evaluates {@code configKey} as a JSON object, against {@code context}.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or not a JSON object
   * @param context evaluated against targeting rules; may be null
   * @return the evaluated value, or {@code defaultValue}
   */
  Map<String, Object> getJsonObject(String configKey, Map<String, Object> defaultValue, Context context);

  /**
   * Evaluates {@code configKey} as a JSON array. Values inside are String, Number, Boolean, List,
   * Map, or null.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or not a JSON array
   * @return the evaluated value, or {@code defaultValue}
   */
  List<Object> getJsonArray(String configKey, List<Object> defaultValue);

  /**
   * Evaluates {@code configKey} as a JSON array, against {@code context}.
   *
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or not a JSON array
   * @param context evaluated against targeting rules; may be null
   * @return the evaluated value, or {@code defaultValue}
   */
  List<Object> getJsonArray(String configKey, List<Object> defaultValue, Context context);

  /**
   * The counterpart to getValue in the other ConfigDirector SDKs: the type is taken from {@code
   * defaultValue}, which must be a Boolean, String, Integer, Long, Double, Float, Map or List.
   *
   * @param <T> the type of the default, and of the value returned
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or will not coerce to
   *     its type
   * @return the evaluated value, or {@code defaultValue}
   */
  <T> T getValue(String configKey, T defaultValue);

  /**
   * Evaluates {@code configKey} as the type of {@code defaultValue}, against {@code context}.
   *
   * @param <T> the type of the default, and of the value returned
   * @param configKey the config to read
   * @param defaultValue returned when the config is missing, unreachable, or will not coerce to
   *     its type
   * @param context evaluated against targeting rules; may be null
   * @return the evaluated value, or {@code defaultValue}
   */
  <T> T getValue(String configKey, T defaultValue, Context context);

  /**
   * Every config the SDK currently holds, evaluated, before type parsing.
   *
   * <p>Intended for handing state to a client SDK to hydrate with. It records no telemetry, since
   * the SDK that receives the state reports its own evaluations.
   *
   * @return the evaluated state by key, or an empty map before the first config state arrives
   */
  Map<String, ConfigState> getAllConfigs();

  /**
   * Every config the SDK currently holds, evaluated against {@code context}.
   *
   * @param context evaluated against targeting rules; may be null
   * @return the evaluated state by key, or an empty map before the first config state arrives
   */
  Map<String, ConfigState> getAllConfigs(Context context);

  /**
   * The named configs, evaluated against {@code context}.
   *
   * @param context evaluated against targeting rules; may be null
   * @param configKeys the keys to include, or null for every key the SDK holds
   * @return the evaluated state by key, or an empty map before the first config state arrives
   */
  Map<String, ConfigState> getAllConfigs(Context context, List<String> configKeys);

  /**
   * Watches {@code configKey} as a boolean.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing or not a boolean
   * @param onChange receives each newly evaluated value
   * @return a handle that cancels this watch
   */
  Subscription watchBoolean(String configKey, boolean defaultValue, Consumer<Boolean> onChange);

  /**
   * Watches {@code configKey} as a boolean, against {@code context}.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing or not a boolean
   * @param onChange receives each newly evaluated value
   * @param context evaluated against targeting rules; may be null
   * @return a handle that cancels this watch
   */
  Subscription watchBoolean(
      String configKey, boolean defaultValue, Consumer<Boolean> onChange, Context context);

  /**
   * Watches {@code configKey} as text.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing
   * @param onChange receives each newly evaluated value
   * @return a handle that cancels this watch
   */
  Subscription watchString(String configKey, String defaultValue, Consumer<String> onChange);

  /**
   * Watches {@code configKey} as text, against {@code context}.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing
   * @param onChange receives each newly evaluated value
   * @param context evaluated against targeting rules; may be null
   * @return a handle that cancels this watch
   */
  Subscription watchString(
      String configKey, String defaultValue, Consumer<String> onChange, Context context);

  /**
   * Watches {@code configKey} as a whole number.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing or not a whole number
   * @param onChange receives each newly evaluated value
   * @return a handle that cancels this watch
   */
  Subscription watchInteger(String configKey, int defaultValue, Consumer<Integer> onChange);

  /**
   * Watches {@code configKey} as a whole number, against {@code context}.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing or not a whole number
   * @param onChange receives each newly evaluated value
   * @param context evaluated against targeting rules; may be null
   * @return a handle that cancels this watch
   */
  Subscription watchInteger(
      String configKey, int defaultValue, Consumer<Integer> onChange, Context context);

  /**
   * Watches {@code configKey} as a number.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing or not a number
   * @param onChange receives each newly evaluated value
   * @return a handle that cancels this watch
   */
  Subscription watchDouble(String configKey, double defaultValue, Consumer<Double> onChange);

  /**
   * Watches {@code configKey} as a number, against {@code context}.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing or not a number
   * @param onChange receives each newly evaluated value
   * @param context evaluated against targeting rules; may be null
   * @return a handle that cancels this watch
   */
  Subscription watchDouble(
      String configKey, double defaultValue, Consumer<Double> onChange, Context context);

  /**
   * Watches {@code configKey} as a JSON object.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing or not a JSON object
   * @param onChange receives each newly evaluated value
   * @return a handle that cancels this watch
   */
  Subscription watchJsonObject(
      String configKey, Map<String, Object> defaultValue, Consumer<Map<String, Object>> onChange);

  /**
   * Watches {@code configKey} as a JSON object, against {@code context}.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing or not a JSON object
   * @param onChange receives each newly evaluated value
   * @param context evaluated against targeting rules; may be null
   * @return a handle that cancels this watch
   */
  Subscription watchJsonObject(
      String configKey,
      Map<String, Object> defaultValue,
      Consumer<Map<String, Object>> onChange,
      Context context);

  /**
   * Watches {@code configKey} as a JSON array.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing or not a JSON array
   * @param onChange receives each newly evaluated value
   * @return a handle that cancels this watch
   */
  Subscription watchJsonArray(
      String configKey, List<Object> defaultValue, Consumer<List<Object>> onChange);

  /**
   * Watches {@code configKey} as a JSON array, against {@code context}.
   *
   * @param configKey the config to watch
   * @param defaultValue used when the updated value is missing or not a JSON array
   * @param onChange receives each newly evaluated value
   * @param context evaluated against targeting rules; may be null
   * @return a handle that cancels this watch
   */
  Subscription watchJsonArray(
      String configKey,
      List<Object> defaultValue,
      Consumer<List<Object>> onChange,
      Context context);

  /**
   * Watches {@code configKey} as the type of {@code defaultValue}.
   *
   * @param <T> the type of the default, and of the value passed to {@code onChange}
   * @param configKey the config to watch
   * @param defaultValue used when the updated value will not coerce to its type
   * @param onChange receives each newly evaluated value
   * @return a handle that cancels this watch
   * @deprecated replaced by the per-type watches, which mirror the getters: {@link
   *     #watchBoolean(String, boolean, Consumer)} and its siblings.
   */
  @Deprecated
  <T> Subscription watch(String configKey, T defaultValue, Consumer<T> onChange);

  /**
   * Watches {@code configKey} as the type of {@code defaultValue}, against {@code context}.
   *
   * @param <T> the type of the default, and of the value passed to {@code onChange}
   * @param configKey the config to watch
   * @param defaultValue used when the updated value will not coerce to its type
   * @param onChange receives each newly evaluated value
   * @param context evaluated against targeting rules; may be null
   * @return a handle that cancels this watch
   * @deprecated replaced by the per-type watches, which mirror the getters: {@link
   *     #watchBoolean(String, boolean, Consumer, Context)} and its siblings.
   */
  @Deprecated
  <T> Subscription watch(String configKey, T defaultValue, Consumer<T> onChange, Context context);

  /**
   * Cancels every watch on one config.
   *
   * @param configKey the config to stop watching
   */
  void unwatch(String configKey);

  /** Cancels every watch on every config. */
  void unwatchAll();

  /**
   * Calls {@code handler} once, when the first config state arrives. A handler registered after
   * that point is never called.
   *
   * @param handler receives the event
   * @return a handle that cancels this registration
   */
  Subscription onClientReady(Consumer<ClientReadyEvent> handler);

  /**
   * Calls {@code handler} every time new config state arrives.
   *
   * @param handler receives the keys the update carried
   * @return a handle that cancels this registration
   */
  Subscription onConfigsUpdated(Consumer<ConfigsUpdatedEvent> handler);

  /**
   * Calls {@code handler} every time a config is evaluated, including evaluations that returned
   * the caller's default. Handlers run on the calling thread, so one that blocks delays the getter
   * that triggered it.
   *
   * @param handler receives what was asked for and what came back
   * @return a handle that cancels this registration
   */
  Subscription onConfigEvaluated(Consumer<ConfigEvaluatedEvent> handler);

  /**
   * Closes connections, reports whatever telemetry is pending, and cancels every watch and
   * handler. Calling it twice is harmless.
   */
  @Override
  void close();

  /**
   * Closes the client, spending no more than {@code timeout} on the whole shutdown.
   *
   * <p>Stopping the connection and sending the last telemetry report both come out of this one
   * budget, rather than each waiting out a timeout of its own. A shutdown therefore takes at most
   * about as long as it is given, which is what makes it safe to call from a handler running
   * under a container's termination grace period.
   *
   * <p>Whatever the budget does not stretch to is given up rather than waited for: a connection
   * still unwinding is left to its own daemon thread, and a final telemetry report with no time
   * left to send it is dropped. {@link Duration#ZERO} closes without waiting for anything.
   *
   * @param timeout how long the whole shutdown may take; zero or negative waits for nothing
   * @throws NullPointerException if timeout is null
   */
  void close(Duration timeout);
}
