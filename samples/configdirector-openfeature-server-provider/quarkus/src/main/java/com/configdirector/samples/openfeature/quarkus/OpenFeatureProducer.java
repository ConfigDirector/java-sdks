package com.configdirector.samples.openfeature.quarkus;

import com.configdirector.ConnectionMode;
import com.configdirector.openfeature.ConfigDirectorProvider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class OpenFeatureProducer {

  private static final Logger log = LoggerFactory.getLogger(OpenFeatureProducer.class);

  @Produces
  @Singleton
  @Startup
  public OpenFeatureAPI openFeatureApi(SampleConfig config) {
    ConnectionMode mode = modeOf(config.mode());
    Logger sdkLogger = LoggerFactory.getLogger("sample.configdirector");

    ConfigDirectorProvider provider =
        new ConfigDirectorProvider(
            config.serverKey(),
            options ->
                options
                    .metadata("openfeature-quarkus-sample", "1.0.0")
                    .logger(sdkLogger)
                    .connection(
                        connection -> {
                          connection.mode(mode);
                          connection.timeout(config.timeout());
                          config
                              .baseUrl()
                              .filter(url -> !url.isBlank())
                              .ifPresent(connection::url);
                        }));

    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.onProviderConfigurationChanged(
        details -> log.info("Configs updated: {}", details.getFlagsChanged()));
    api.setProviderAndWait(provider);

    log.info("ConfigDirector OpenFeature provider registered once at startup (mode={})", mode);
    return api;
  }

  @Produces
  @Singleton
  public Client openFeatureClient(OpenFeatureAPI api) {
    return api.getClient();
  }

  public void shutdown(@Disposes OpenFeatureAPI api) {
    api.shutdown();
  }

  private static ConnectionMode modeOf(String mode) {
    return switch (mode.toLowerCase(Locale.ROOT)) {
      case "polling" -> ConnectionMode.POLLING;
      default -> ConnectionMode.STREAMING;
    };
  }
}
