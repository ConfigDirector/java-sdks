package com.configdirector.openfeature;

import com.configdirector.ClientOptions;
import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigsUpdatedEvent;
import com.configdirector.ConnectionMode;
import com.configdirector.Context;
import com.configdirector.internal.SdkIdentity;
import com.configdirector.internal.client.ClientFactory;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * An OpenFeature provider backed by the ConfigDirector Java server SDK.
 *
 * <p>Register it with the OpenFeature API and read values through an OpenFeature client. The
 * provider owns a {@link ConfigDirectorClient}: it connects when OpenFeature initializes the
 * provider, and closes when OpenFeature shuts it down. Targeting rules are evaluated locally, so an
 * evaluation makes no network calls.
 *
 * <pre>{@code
 * OpenFeatureAPI api = OpenFeatureAPI.getInstance();
 * api.setProviderAndWait(new ConfigDirectorProvider(sdkKey));
 *
 * boolean enabled = api.getClient().getBooleanValue("new-checkout", false);
 * }</pre>
 *
 * <p>The OpenFeature evaluation context maps onto the ConfigDirector {@link Context}: the targeting
 * key, or failing that an {@code id} attribute, becomes the context's id, a top-level {@code kind}
 * its kind, {@code name} its name, a {@code traits} structure its traits, and a boolean {@code
 * anonymous} its anonymous flag. Leaving out {@code kind} means {@code user}, and a {@code kind}
 * inside {@code traits} is an ordinary trait. A context whose kind is invalid, including one that
 * is not a string, is ignored as a whole: the evaluation still resolves successfully, to the value
 * for no context, and the SDK logs an error.
 *
 * <p>If no config arrives within the connection timeout, or ConfigDirector rejects the server SDK
 * key, initialization fails with OpenFeature's {@link ProviderNotReadyError}: {@code
 * setProviderAndWait} throws it, and OpenFeature reports the {@code ERROR} state. Evaluations
 * return their in-code default values with the error code {@code PROVIDER_NOT_READY} until config
 * arrives. After a timeout the provider keeps connecting, and reports {@code READY} once config
 * arrives. To start without waiting for ConfigDirector, call {@code setProvider} instead, or catch
 * the exception:
 *
 * <pre>{@code
 * try {
 *   api.setProviderAndWait(new ConfigDirectorProvider(sdkKey));
 * } catch (ProviderNotReadyError error) {
 *   log.warn(
 *       "Starting on in-code default values until ConfigDirector sends config: {}",
 *       error.getMessage());
 * }
 * }</pre>
 *
 * <p>In {@link ConnectionMode#OFFLINE} mode the provider never connects and serves the values it
 * was given. If those values cannot be loaded, initialization fails: {@code setProviderAndWait}
 * throws, and the provider is left in the error state.
 */
public final class ConfigDirectorProvider extends EventProvider {

  private static final String NAME = "ConfigDirectorProvider";
  private static final SdkIdentity IDENTITY =
      SdkIdentity.of("java-openfeature-server-provider", ConfigDirectorProvider.class);
  private static final ThreadLocal<ConfigEvaluation> CURRENT_EVALUATION = new ThreadLocal<>();
  private static final String NO_CONFIG_IN_TIME =
      "No config arrived from ConfigDirector within the connection timeout. Flags resolve to their "
          + "in-code default values until config arrives.";

  private static final Duration RECORDED_FAILURE_POLL_INTERVAL = Duration.ofMillis(10);
  private static final VarHandle STATE_MANAGER;
  private static final VarHandle RECORDED_STATE;

  static {
    try {
      MethodHandles.Lookup eventProviderAccess =
          MethodHandles.privateLookupIn(EventProvider.class, MethodHandles.lookup());
      Class<?> stateManagerClass =
          eventProviderAccess.findClass("dev.openfeature.sdk.FeatureProviderStateManager");
      STATE_MANAGER =
          eventProviderAccess.findVarHandle(
              EventProvider.class,
              "eventProviderListener",
              eventProviderAccess.findClass("dev.openfeature.sdk.EventProviderListener"));
      RECORDED_STATE =
          MethodHandles.privateLookupIn(stateManagerClass, MethodHandles.lookup())
              .findVarHandle(stateManagerClass, "state", AtomicReference.class);
    } catch (ReflectiveOperationException unsupported) {
      throw new IllegalStateException(
          "ConfigDirectorProvider does not support this version of the OpenFeature Java SDK: it "
              + "cannot find where OpenFeature records a provider's state. Use the "
              + "dev.openfeature:sdk version this release of the provider depends on.",
          unsupported);
    }
  }

  private static final Executor ON_A_NEW_DAEMON_THREAD =
      readinessCheck -> {
        Thread thread = new Thread(readinessCheck, "configdirector-openfeature-readiness-check");
        thread.setDaemon(true);
        thread.start();
      };

  private enum Startup {
    INITIALIZING,
    AWAITING_RECORDED_FAILURE,
    AWAITING_CONFIG,
    READY
  }

  private final ConfigDirectorClient client;
  private final Executor readinessCheckExecutor;
  private final Object lock = new Object();
  private final CountDownLatch shutdownStarted = new CountDownLatch(1);
  private volatile CompletableFuture<Void> scheduledReadinessCheck =
      CompletableFuture.completedFuture(null);
  private Startup startup = Startup.INITIALIZING;

  /**
   * A provider whose client uses default settings.
   *
   * @param serverSdkKey a secret; do not commit it to source control
   */
  public ConfigDirectorProvider(String serverSdkKey) {
    this(serverSdkKey, options -> {});
  }

  /**
   * A provider whose client uses the settings {@code configure} adjusts. These are the same
   * settings {@link ConfigDirector#client(String, Consumer)} accepts.
   *
   * @param serverSdkKey a secret; do not commit it to source control. May be null or blank when
   *     the connection mode is {@link ConnectionMode#OFFLINE}
   * @param configure receives the settings to adjust before the client is built
   */
  public ConfigDirectorProvider(String serverSdkKey, Consumer<ClientOptions> configure) {
    this(serverSdkKey, configure, ON_A_NEW_DAEMON_THREAD);
  }

  ConfigDirectorProvider(
      String serverSdkKey, Consumer<ClientOptions> configure, Executor readinessCheckExecutor) {
    this.client = ClientFactory.create(serverSdkKey, configure, IDENTITY);
    this.readinessCheckExecutor = readinessCheckExecutor;
    this.client.onConfigEvaluated(event -> CURRENT_EVALUATION.set(event.evaluation()));
    this.client.onConfigsUpdated(
        event ->
            emitProviderConfigurationChanged(
                ProviderEventDetails.builder().flagsChanged(changedFlags(event)).build()));
    this.client.onClientReady(event -> reportReadyIfConfigArrived());
  }

  /**
   * A provider without a server SDK key, which only an offline client can run without. These are
   * the same settings {@link ConfigDirector#client(Consumer)} accepts.
   *
   * <pre>{@code
   * api.setProviderAndWait(new ConfigDirectorProvider(options -> options
   *     .connection(connection -> connection
   *         .mode(ConnectionMode.OFFLINE)
   *         .file(Path.of("flags/new-checkout-on.json")))));
   * }</pre>
   *
   * @param configure receives the settings to adjust before the client is built; it must set the
   *     connection mode to {@link ConnectionMode#OFFLINE}
   * @throws com.configdirector.ConfigDirectorValidationException if the connection mode is not
   *     offline
   */
  public ConfigDirectorProvider(Consumer<ClientOptions> configure) {
    this(null, configure);
  }

  private static List<String> changedFlags(ConfigsUpdatedEvent event) {
    List<String> flags = new ArrayList<>(event.keys());
    flags.addAll(event.removedKeys());
    return flags;
  }

  @Override
  public Metadata getMetadata() {
    return () -> NAME;
  }

  @Override
  public void initialize(EvaluationContext evaluationContext) {
    client.initialize();
    synchronized (lock) {
      if (client.isReady()) {
        startup = Startup.READY;
        return;
      }
      startup = Startup.AWAITING_RECORDED_FAILURE;
    }
    scheduledReadinessCheck =
        CompletableFuture.runAsync(
            this::reportReadyOnceTheFailureIsRecorded, readinessCheckExecutor);
    throw new ProviderNotReadyError(NO_CONFIG_IN_TIME);
  }

  @Override
  public void shutdown() {
    shutdownStarted.countDown();
    scheduledReadinessCheck.join();
    client.close();
    super.shutdown();
  }

  @Override
  public ProviderEvaluation<Boolean> getBooleanEvaluation(
      String key, Boolean defaultValue, EvaluationContext evaluationContext) {
    return evaluate(key, defaultValue, evaluationContext);
  }

  @Override
  public ProviderEvaluation<String> getStringEvaluation(
      String key, String defaultValue, EvaluationContext evaluationContext) {
    return evaluate(key, defaultValue, evaluationContext);
  }

  @Override
  public ProviderEvaluation<Integer> getIntegerEvaluation(
      String key, Integer defaultValue, EvaluationContext evaluationContext) {
    return evaluate(key, defaultValue, evaluationContext);
  }

  @Override
  public ProviderEvaluation<Double> getDoubleEvaluation(
      String key, Double defaultValue, EvaluationContext evaluationContext) {
    return evaluate(key, defaultValue, evaluationContext);
  }

  @Override
  public ProviderEvaluation<Long> getLongEvaluation(
      String key, Long defaultValue, EvaluationContext evaluationContext) {
    return evaluate(key, defaultValue, evaluationContext);
  }

  @Override
  public ProviderEvaluation<Value> getObjectEvaluation(
      String key, Value defaultValue, EvaluationContext evaluationContext) {
    return evaluate(
        key,
        ValueMapper.toJava(defaultValue),
        evaluationContext,
        (value, servedInCodeDefault) ->
            servedInCodeDefault ? defaultValue : ValueMapper.toValue(value));
  }

  private void reportReadyOnceTheFailureIsRecorded() {
    while (!openFeatureRecordedTheFailure()) {
      if (stopRequestedWithin(RECORDED_FAILURE_POLL_INTERVAL)) {
        return;
      }
    }
    synchronized (lock) {
      startup = Startup.AWAITING_CONFIG;
    }
    reportReadyIfConfigArrived();
  }

  private boolean openFeatureRecordedTheFailure() {
    Object stateManager = STATE_MANAGER.get(this);
    return stateManager != null
        && ((AtomicReference<?>) RECORDED_STATE.get(stateManager)).get() == ProviderState.ERROR;
  }

  private boolean stopRequestedWithin(Duration timeout) {
    try {
      return shutdownStarted.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return true;
    }
  }

  private void reportReadyIfConfigArrived() {
    synchronized (lock) {
      boolean configArrivedAfterTheFailure =
          startup == Startup.AWAITING_CONFIG && client.isReady();
      if (!configArrivedAfterTheFailure) {
        return;
      }
      startup = Startup.READY;
    }
    emitProviderReady(ProviderEventDetails.builder().build());
  }

  private <T> ProviderEvaluation<T> evaluate(
      String key, T defaultValue, EvaluationContext evaluationContext) {
    return evaluate(key, defaultValue, evaluationContext, (value, servedInCodeDefault) -> value);
  }

  private <T, R> ProviderEvaluation<R> evaluate(
      String key,
      T defaultValue,
      EvaluationContext evaluationContext,
      BiFunction<T, Boolean, R> convert) {
    try {
      T value = client.getValue(key, defaultValue, ContextMapper.toContext(evaluationContext));
      ConfigEvaluation evaluation = CURRENT_EVALUATION.get();
      boolean servedInCodeDefault = evaluation == null || evaluation.isDefault();
      return Resolutions.of(convert.apply(value, servedInCodeDefault), evaluation);
    } finally {
      CURRENT_EVALUATION.remove();
    }
  }
}
