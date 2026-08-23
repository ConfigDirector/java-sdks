package com.configdirector.internal.client;

import com.configdirector.ClientReadyEvent;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.ConfigEvaluatedEvent;
import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigState;
import com.configdirector.ConfigType;
import com.configdirector.ConfigsUpdatedEvent;
import com.configdirector.ConnectionMode;
import com.configdirector.ConnectionOptions;
import com.configdirector.Context;
import com.configdirector.EvaluationReason;
import com.configdirector.Metadata;
import com.configdirector.Subscription;
import com.configdirector.TelemetryOptions;
import com.configdirector.internal.SdkIdentity;
import com.configdirector.internal.evaluation.Config;
import com.configdirector.internal.evaluation.ConfigEvaluator;
import com.configdirector.internal.evaluation.EvaluationContext;
import com.configdirector.internal.telemetry.TelemetryCollector;
import com.configdirector.internal.telemetry.TelemetryCollectorOptions;
import com.configdirector.internal.telemetry.TelemetryValue;
import com.configdirector.internal.transport.ConfigBundle;
import com.configdirector.internal.transport.HttpClient;
import com.configdirector.internal.transport.Transport;
import com.configdirector.internal.transport.TransportOptions;
import com.configdirector.internal.transport.Transports;
import com.configdirector.internal.value.ParseResult;
import com.configdirector.internal.value.ValueParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;

public final class DefaultConfigDirectorClient implements ConfigDirectorClient {

  private static final String DEFAULT_BASE_URL = "https://server-sdk-api.configdirector.com";

  // The longest timeout OkHttp accepts. ConnectionOptions bounds the one it carries; this bounds
  // the one a caller hands straight to initialize. Past it OkHttp rejects the request, which
  // initialization would otherwise report only as a client that never becomes ready.
  private static final Duration LONGEST_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);


  private final Logger logger;
  private final Metadata metadata;
  private final ConnectionOptions connection;
  private final String baseUrl;
  private final HttpClient http;
  private final ConfigEvaluator evaluator;
  private final Transport transport;
  private final TelemetryCollector telemetry;

  // Held by whatever replaces config state -- a bundle, or close(). Readers do not take it:
  // they read the snapshot below, which is only ever swapped, never edited in place.
  private final Object lock = new Object();
  private final CountDownLatch ready = new CountDownLatch(1);
  private final Map<String, List<Watcher>> watchers = new ConcurrentHashMap<>();
  private final List<Consumer<ClientReadyEvent>> readyHandlers = new CopyOnWriteArrayList<>();
  private final List<Consumer<ConfigsUpdatedEvent>> updateHandlers = new CopyOnWriteArrayList<>();
  private final List<Consumer<ConfigEvaluatedEvent>> evaluationHandlers = new CopyOnWriteArrayList<>();

  // Null until the first bundle arrives, which is what separates "not ready" from "ready but the
  // server does not know this key". Immutable once published, so a config read is a volatile read
  // and a map lookup -- no lock on the path every getX() call takes.
  private volatile Map<String, Config> configs;
  private volatile boolean closed;

  public DefaultConfigDirectorClient(
      String serverSdkKey,
      Metadata metadata,
      ConnectionOptions connection,
      TelemetryOptions telemetry,
      Logger logger) {
    if (serverSdkKey == null || serverSdkKey.isBlank()) {
      throw new ConfigDirectorValidationException(
          "No server SDK key was provided, the client cannot be instantiated without a valid "
              + "server SDK key");
    }

    this.logger = logger;
    this.metadata = metadata == null ? Metadata.empty() : metadata;
    this.connection = connection == null ? ConnectionOptions.defaults() : connection;
    this.baseUrl = validatedUrl(this.connection.url());
    this.evaluator = new ConfigEvaluator(logger);

    // One pool for every request/response call this client makes -- polling and telemetry both.
    // Owned here so close() releases the connections it opened, rather than leaving them in a pool
    // shared across the process.
    this.http = new HttpClient();
    this.transport =
        Transports.create(
            modeOf(this.connection.mode()),
            new TransportOptions(
                serverSdkKey,
                baseUrl,
                metaContext(this.metadata),
                logger,
                this::onBundle,
                http,
                this.connection.pollingInterval()));

    TelemetryOptions telemetryOptions = telemetry == null ? TelemetryOptions.defaults() : telemetry;
    this.telemetry =
        new TelemetryCollector(
            new TelemetryCollectorOptions(
                serverSdkKey,
                baseUrl,
                logger,
                http,
                telemetryOptions.eventQueueLimit(),
                telemetryOptions.flushInterval(),
                TelemetryCollector.INITIAL_FLUSH_DELAY));
  }

  @Override
  public void initialize() {
    initialize(connection.timeout());
  }

  @Override
  public void initialize(Duration timeout) {
    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
      throw new ConfigDirectorValidationException(
          "Invalid timeout '" + timeout + "'. The timeout must be a positive duration.");
    }
    if (timeout.compareTo(LONGEST_TIMEOUT) > 0) {
      throw new ConfigDirectorValidationException(
          "Invalid timeout '"
              + timeout
              + "'. It must be no longer than "
              + LONGEST_TIMEOUT.toMillis()
              + "ms (about "
              + LONGEST_TIMEOUT.toDays()
              + " days), which is the longest the HTTP client accepts.");
    }
    raiseIfClosed();
    logger.debug(
        "[ConfigDirectorClient] Initializing in {} mode against {} with a {} timeout",
        connection.mode(),
        baseUrl,
        timeout);

    long started = System.nanoTime();
    try {
      transport.connect(timeout);
    } catch (RuntimeException error) {
      logger.error("[ConfigDirectorClient] An error occurred during initialization", error);
      return;
    }

    Duration remaining = timeout.minus(Duration.ofNanos(System.nanoTime() - started));
    if (!remaining.isNegative() && !remaining.isZero()) {
      awaitReady(remaining);
    }

    if (!isReady()) {
      logger.warn(
          "[ConfigDirectorClient] Timed out waiting for initialization after {}. {}",
          timeout,
          connection.mode() == ConnectionMode.STREAMING
              ? "The client will continue to retry since there were no fatal errors detected. "
                  + "Configs will return the default value until the connection succeeds."
              : "Since the client was configured without streaming, configs may not update and "
                  + "will always return the default value.");
    }
  }

  @Override
  public boolean isReady() {
    return !closed && configs != null;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      configs = null;
    }
    watchers.clear();
    readyHandlers.clear();
    updateHandlers.clear();
    evaluationHandlers.clear();

    // Releases anyone still blocked in initialize().
    ready.countDown();
    transport.close();
    // Reports whatever was evaluated since the last flush. Before the pool closes: that final
    // report is the client's last request, and it needs the pool still open to send it.
    telemetry.close();
    http.close();
    logger.debug("[ConfigDirectorClient] close() has been called, the client is now closed");
  }

  private void awaitReady(Duration timeout) {
    try {
      ready.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void onBundle(ConfigBundle bundle) {
    boolean firstBundle;
    Map<String, List<Watcher>> affected = new LinkedHashMap<>();
    synchronized (lock) {
      if (closed) {
        return;
      }
      Map<String, Config> current = configs;
      Map<String, Config> merged;
      if (current == null || bundle.kind() == ConfigBundle.BundleKind.FULL) {
        merged = new LinkedHashMap<>(bundle.configs());
        firstBundle = ready.getCount() > 0;
      } else {
        // A delta merges onto a copy rather than onto the live map: readers hold a reference to
        // whatever was published last, and it has to stay whole while they walk it.
        merged = new LinkedHashMap<>(current);
        merged.putAll(bundle.configs());
        firstBundle = false;
      }
      configs = Collections.unmodifiableMap(merged);
    }

    // Snapshotted outside the lock, so a user callback cannot observe the list being edited from
    // under it.
    bundle
        .configs()
        .keySet()
        .forEach(
            key -> {
              List<Watcher> entries = watchers.get(key);
              if (entries != null && !entries.isEmpty()) {
                affected.put(key, List.copyOf(entries));
              }
            });

    List<String> keys = new ArrayList<>(new TreeMap<>(bundle.configs()).keySet());
    logger.debug("[ConfigDirectorClient] Config state updated with {} key(s): {}", keys.size(), keys);
    emit(updateHandlers, new ConfigsUpdatedEvent(keys), "configsUpdated");
    notifyWatchers(affected, bundle.configs());

    if (firstBundle) {
      ready.countDown();
      emit(readyHandlers, new ClientReadyEvent(), "clientReady");
      logger.debug("[ConfigDirectorClient] Received the initial payload, the client is ready");
    }
  }

  // Evaluated against the bundle rather than the merged state: a watcher only fires for a key the
  // update carried, and for those two are the same definition.
  private void notifyWatchers(Map<String, List<Watcher>> affected, Map<String, Config> updated) {
    affected.forEach(
        (key, entries) -> {
          Config definition = updated.get(key);
          for (Watcher watcher : entries) {
            try {
              watcher.notify(evaluate(key, definition, watcher.defaultValue(), watcher.context()));
            } catch (RuntimeException error) {
              // One faulty watcher must not cost the others their update, and must not take down
              // the transport thread this runs on.
              logger.error("[ConfigDirectorClient] A watcher for {} threw", key, error);
            }
          }
        });
  }

  @Override
  public boolean getBoolean(String configKey, boolean defaultValue) {
    return getBoolean(configKey, defaultValue, null);
  }

  @Override
  public boolean getBoolean(String configKey, boolean defaultValue, Context context) {
    return (Boolean) resolve(configKey, defaultValue, context);
  }

  @Override
  public String getString(String configKey, String defaultValue) {
    return getString(configKey, defaultValue, null);
  }

  @Override
  public String getString(String configKey, String defaultValue, Context context) {
    return (String) resolve(configKey, defaultValue, context);
  }

  @Override
  public int getInteger(String configKey, int defaultValue) {
    return getInteger(configKey, defaultValue, null);
  }

  @Override
  public int getInteger(String configKey, int defaultValue, Context context) {
    return (Integer) resolve(configKey, defaultValue, context);
  }

  @Override
  public double getDouble(String configKey, double defaultValue) {
    return getDouble(configKey, defaultValue, null);
  }

  @Override
  public double getDouble(String configKey, double defaultValue, Context context) {
    return (Double) resolve(configKey, defaultValue, context);
  }

  @Override
  public Map<String, Object> getJsonObject(String configKey, Map<String, Object> defaultValue) {
    return getJsonObject(configKey, defaultValue, null);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> getJsonObject(
      String configKey, Map<String, Object> defaultValue, Context context) {
    return (Map<String, Object>) resolve(configKey, defaultValue, context);
  }

  @Override
  public List<Object> getJsonArray(String configKey, List<Object> defaultValue) {
    return getJsonArray(configKey, defaultValue, null);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<Object> getJsonArray(String configKey, List<Object> defaultValue, Context context) {
    return (List<Object>) resolve(configKey, defaultValue, context);
  }

  @Override
  public <T> T getValue(String configKey, T defaultValue) {
    return getValue(configKey, defaultValue, null);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T getValue(String configKey, T defaultValue, Context context) {
    // Safe because every path through the parser returns either the parsed value in the default's
    // own type or the default itself.
    return (T) resolve(configKey, defaultValue, context);
  }

  private Object resolve(String configKey, Object defaultValue, Context context) {
    validateConfigKey(configKey);
    validateDefault(defaultValue);

    Map<String, Config> snapshot = configs;
    Config definition = snapshot == null ? null : snapshot.get(configKey);
    return evaluate(configKey, definition, defaultValue, context);
  }

  private Object evaluate(String configKey, Config definition, Object defaultValue, Context context) {
    if (definition == null) {
      EvaluationReason reason =
          isReady() ? EvaluationReason.CONFIG_STATE_MISSING : EvaluationReason.CLIENT_NOT_READY;
      logger.debug(
          "[ConfigDirectorClient] No config state found for {}, returning the default value",
          configKey);
      report(
          new ConfigEvaluation(
              configKey,
              defaultValue,
              true,
              reason,
              TelemetryValue.idFor(defaultValue, null),
              context),
          defaultValue,
          null);
      return defaultValue;
    }

    ConfigState state = evaluator.evaluate(definition, new EvaluationContext(context, metadata));
    ParseResult result = ValueParser.parse(state, defaultValue);
    logger.debug("[ConfigDirectorClient] Evaluated {} to {}", configKey, result.value());
    // The server identifies every value it sends, so the fallback only comes into play for a
    // default returned from here and for a payload that predates value IDs.
    String valueId =
        result.valueId() == null
            ? TelemetryValue.idFor(result.value(), state.type())
            : result.valueId();
    report(
        new ConfigEvaluation(
            configKey, result.value(), result.usedDefault(), result.reason(), valueId, context),
        defaultValue,
        state.type());
    return result.value();
  }

  private void report(ConfigEvaluation evaluation, Object defaultValue, ConfigType type) {
    telemetry.recordEvaluation(evaluation, defaultValue, type);
    if (!evaluationHandlers.isEmpty()) {
      emit(evaluationHandlers, new ConfigEvaluatedEvent(evaluation), "configEvaluated");
    }
  }

  @Override
  public Map<String, ConfigState> getAllConfigs() {
    return getAllConfigs(null, null);
  }

  @Override
  public Map<String, ConfigState> getAllConfigs(Context context) {
    return getAllConfigs(context, null);
  }

  @Override
  public Map<String, ConfigState> getAllConfigs(Context context, List<String> configKeys) {
    Map<String, Config> definitions = configs;
    if (closed || definitions == null) {
      return Map.of();
    }

    // A set, so filtering stays linear in the number of configs rather than scanning the requested
    // keys once per config. Iterating the definitions rather than the request keeps the result in
    // config order and collapses a key asked for twice.
    Set<String> requested = configKeys == null ? null : Set.copyOf(configKeys);
    EvaluationContext evaluationContext = new EvaluationContext(context, metadata);
    Map<String, ConfigState> evaluated = new LinkedHashMap<>();
    definitions.forEach(
        (key, config) -> {
          if (requested == null || requested.contains(key)) {
            evaluated.put(key, evaluator.evaluate(config, evaluationContext));
          }
        });
    return evaluated;
  }

  @Override
  public <T> Subscription watch(String configKey, T defaultValue, Consumer<T> onChange) {
    return watch(configKey, defaultValue, onChange, null);
  }

  @Override
  public <T> Subscription watch(
      String configKey, T defaultValue, Consumer<T> onChange, Context context) {
    validateConfigKey(configKey);
    validateDefault(defaultValue);
    if (onChange == null) {
      throw new ConfigDirectorValidationException(
          "Invalid callback. The watch callback must accept the new value.");
    }

    Watcher watcher = new Watcher(onChange, defaultValue, context);
    watchers.computeIfAbsent(configKey, key -> new CopyOnWriteArrayList<>()).add(watcher);
    return () -> {
      List<Watcher> entries = watchers.get(configKey);
      if (entries != null) {
        entries.remove(watcher);
      }
    };
  }

  @Override
  public void unwatch(String configKey) {
    watchers.remove(configKey);
  }

  @Override
  public void unwatchAll() {
    watchers.clear();
  }

  @Override
  public Subscription onClientReady(Consumer<ClientReadyEvent> handler) {
    return register(readyHandlers, handler);
  }

  @Override
  public Subscription onConfigsUpdated(Consumer<ConfigsUpdatedEvent> handler) {
    return register(updateHandlers, handler);
  }

  @Override
  public Subscription onConfigEvaluated(Consumer<ConfigEvaluatedEvent> handler) {
    return register(evaluationHandlers, handler);
  }

  private static <E> Subscription register(List<Consumer<E>> handlers, Consumer<E> handler) {
    if (handler == null) {
      throw new ConfigDirectorValidationException("Event handlers must not be null.");
    }
    handlers.add(handler);
    return () -> handlers.remove(handler);
  }

  private <E> void emit(List<Consumer<E>> handlers, E payload, String name) {
    for (Consumer<E> handler : handlers) {
      try {
        handler.accept(payload);
      } catch (RuntimeException error) {
        // A faulty handler must not break the caller.
        logger.error("[ConfigDirectorClient] A handler for {} threw", name, error);
      }
    }
  }

  private void raiseIfClosed() {
    if (isClosed()) {
      throw new ConfigDirectorValidationException(
          "This client has been closed and can no longer be used. Create a new one instead.");
    }
  }

  private static void validateConfigKey(String configKey) {
    if (configKey == null || configKey.isBlank()) {
      throw new ConfigDirectorValidationException(
          "Invalid config key. The config key must be a non-empty string.");
    }
  }

  private static void validateDefault(Object defaultValue) {
    if (defaultValue == null) {
      throw new ConfigDirectorValidationException(
          "Invalid default value. The default value for a config must not be null.");
    }
    if (!(defaultValue instanceof Boolean
        || defaultValue instanceof String
        || defaultValue instanceof Integer
        || defaultValue instanceof Long
        || defaultValue instanceof Double
        || defaultValue instanceof Float
        || defaultValue instanceof Map
        || defaultValue instanceof List)) {
      throw new ConfigDirectorValidationException(
          "Invalid default value of type "
              + defaultValue.getClass().getName()
              + ". Supported types are Boolean, String, Integer, Long, Double, Float, Map and List.");
    }
  }

  private static String validatedUrl(String url) {
    if (url == null || url.isBlank()) {
      return DEFAULT_BASE_URL;
    }
    java.net.URI parsed;
    try {
      parsed = java.net.URI.create(url.strip());
    } catch (IllegalArgumentException malformed) {
      throw new ConfigDirectorValidationException("Invalid connection URL '" + url + "'.");
    }
    if (!parsed.isAbsolute() || parsed.getHost() == null) {
      throw new ConfigDirectorValidationException(
          "Invalid connection URL '" + url + "'. It must be absolute and name a host.");
    }
    return parsed.toString();
  }

  // The wire format is camelCase, and the server treats every field but the SDK identity as
  // optional, so absent metadata is left out rather than sent as null.
  private static Map<String, String> metaContext(Metadata metadata) {
    Map<String, String> context = new LinkedHashMap<>();
    context.put("sdkName", SdkIdentity.NAME);
    context.put("sdkVersion", SdkIdentity.version());
    if (metadata.appName() != null) {
      context.put("appName", metadata.appName());
    }
    if (metadata.appVersion() != null) {
      context.put("appVersion", metadata.appVersion());
    }
    return context;
  }

  private static com.configdirector.internal.transport.ConnectionMode modeOf(ConnectionMode mode) {
    return switch (mode) {
      case POLLING -> com.configdirector.internal.transport.ConnectionMode.POLLING;
      case ONE_TIME -> com.configdirector.internal.transport.ConnectionMode.ONE_TIME;
      case STREAMING -> com.configdirector.internal.transport.ConnectionMode.STREAMING;
    };
  }

  // Identity comparison keeps two identical watches distinct, so unsubscribing one leaves the
  // other in place.
  private static final class Watcher {

    private final Consumer<?> handler;
    private final Object defaultValue;
    private final Context context;

    Watcher(Consumer<?> handler, Object defaultValue, Context context) {
      this.handler = handler;
      this.defaultValue = defaultValue;
      this.context = context;
    }

    Object defaultValue() {
      return defaultValue;
    }

    Context context() {
      return context;
    }

    @SuppressWarnings("unchecked")
    void notify(Object value) {
      ((Consumer<Object>) handler).accept(value);
    }
  }
}
