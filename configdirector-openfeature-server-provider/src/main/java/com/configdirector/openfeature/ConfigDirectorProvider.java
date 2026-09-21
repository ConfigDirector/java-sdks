package com.configdirector.openfeature;

import com.configdirector.ClientOptions;
import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConfigEvaluation;
import com.configdirector.Context;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.Value;
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
 * key, or failing that an {@code id} attribute, becomes the context's id, {@code name} its name, a
 * {@code traits} structure its traits, and a boolean {@code anonymous} its anonymous flag.
 *
 * <p>If the first config state does not arrive within the configured timeout, initialization still
 * completes and evaluations return their defaults with the error code {@code PROVIDER_NOT_READY}.
 * The provider keeps connecting, and emits {@code PROVIDER_READY} once config state arrives.
 */
public final class ConfigDirectorProvider extends EventProvider {

  private static final String NAME = "ConfigDirectorProvider";
  private static final ThreadLocal<ConfigEvaluation> EVALUATED = new ThreadLocal<>();

  private final ConfigDirectorClient client;

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
   * @param serverSdkKey a secret; do not commit it to source control
   * @param configure receives the settings to adjust before the client is built
   */
  public ConfigDirectorProvider(String serverSdkKey, Consumer<ClientOptions> configure) {
    this.client = ConfigDirector.client(serverSdkKey, configure);
    this.client.onConfigEvaluated(event -> EVALUATED.set(event.evaluation()));
    this.client.onConfigsUpdated(
        event ->
            emitProviderConfigurationChanged(
                ProviderEventDetails.builder().flagsChanged(event.keys()).build()));
  }

  @Override
  public Metadata getMetadata() {
    return () -> NAME;
  }

  @Override
  public void initialize(EvaluationContext evaluationContext) {
    client.initialize();
    if (!client.isReady()) {
      client.onClientReady(event -> emitProviderReady(ProviderEventDetails.builder().build()));
    }
  }

  @Override
  public void shutdown() {
    client.close();
    super.shutdown();
  }

  @Override
  public ProviderEvaluation<Boolean> getBooleanEvaluation(
      String key, Boolean defaultValue, EvaluationContext ctx) {
    return evaluate(key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<String> getStringEvaluation(
      String key, String defaultValue, EvaluationContext ctx) {
    return evaluate(key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Integer> getIntegerEvaluation(
      String key, Integer defaultValue, EvaluationContext ctx) {
    return evaluate(key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Double> getDoubleEvaluation(
      String key, Double defaultValue, EvaluationContext ctx) {
    return evaluate(key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Long> getLongEvaluation(
      String key, Long defaultValue, EvaluationContext ctx) {
    return evaluate(key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Value> getObjectEvaluation(
      String key, Value defaultValue, EvaluationContext ctx) {
    return evaluate(
        key,
        ValueMapper.toJava(defaultValue),
        ctx,
        (value, usedDefault) -> usedDefault ? defaultValue : ValueMapper.toValue(value));
  }

  private <T> ProviderEvaluation<T> evaluate(String key, T defaultValue, EvaluationContext ctx) {
    return evaluate(key, defaultValue, ctx, (value, usedDefault) -> value);
  }

  private <T, R> ProviderEvaluation<R> evaluate(
      String key, T defaultValue, EvaluationContext ctx, BiFunction<T, Boolean, R> convert) {
    try {
      T value = client.getValue(key, defaultValue, ContextMapper.toContext(ctx));
      ConfigEvaluation evaluation = EVALUATED.get();
      boolean usedDefault = evaluation == null || evaluation.isDefault();
      return Resolutions.of(convert.apply(value, usedDefault), evaluation);
    } finally {
      EVALUATED.remove();
    }
  }
}
