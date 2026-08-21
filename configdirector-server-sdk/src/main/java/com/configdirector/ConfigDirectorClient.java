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
 */
public interface ConfigDirectorClient extends AutoCloseable {

  /** Connects and waits for the first config state, bounded by the configured timeout. */
  void initialize();

  /** Connects and waits for the first config state, bounded by {@code timeout}. */
  void initialize(Duration timeout);

  /** Whether config state has arrived. Until it has, every getter returns its default. */
  boolean isReady();

  boolean isClosed();

  boolean getBoolean(String configKey, boolean defaultValue);

  boolean getBoolean(String configKey, boolean defaultValue, Context context);

  String getString(String configKey, String defaultValue);

  String getString(String configKey, String defaultValue, Context context);

  int getInteger(String configKey, int defaultValue);

  int getInteger(String configKey, int defaultValue, Context context);

  double getDouble(String configKey, double defaultValue);

  double getDouble(String configKey, double defaultValue, Context context);

  /** Values inside are String, Number, Boolean, List, Map, or null. */
  Map<String, Object> getJsonObject(String configKey, Map<String, Object> defaultValue);

  Map<String, Object> getJsonObject(String configKey, Map<String, Object> defaultValue, Context context);

  List<Object> getJsonArray(String configKey, List<Object> defaultValue);

  List<Object> getJsonArray(String configKey, List<Object> defaultValue, Context context);

  /**
   * The counterpart to getValue in the other ConfigDirector SDKs: the type is taken from {@code
   * defaultValue}, which must be a Boolean, String, Integer, Long, Double, Float, Map or List.
   */
  <T> T getValue(String configKey, T defaultValue);

  <T> T getValue(String configKey, T defaultValue, Context context);

  /** Every config the SDK currently holds, evaluated, before type parsing. */
  Map<String, ConfigState> getAllConfigs();

  Map<String, ConfigState> getAllConfigs(Context context);

  Map<String, ConfigState> getAllConfigs(Context context, List<String> configKeys);

  /**
   * Calls {@code onChange} whenever an update carries {@code configKey}, with the newly evaluated
   * value. Handlers run on the transport thread, so one that blocks delays later updates.
   */
  <T> Subscription watch(String configKey, T defaultValue, Consumer<T> onChange);

  <T> Subscription watch(String configKey, T defaultValue, Consumer<T> onChange, Context context);

  void unwatch(String configKey);

  void unwatchAll();

  Subscription onClientReady(Consumer<ClientReadyEvent> handler);

  Subscription onConfigsUpdated(Consumer<ConfigsUpdatedEvent> handler);

  Subscription onConfigEvaluated(Consumer<ConfigEvaluatedEvent> handler);

  @Override
  void close();
}
