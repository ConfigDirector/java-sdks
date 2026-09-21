package com.configdirector.samples.openfeature.micronaut;

import com.configdirector.ConnectionMode;
import com.configdirector.openfeature.ConfigDirectorProvider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Factory
public class OpenFeatureFactory {

  private static final Logger log = LoggerFactory.getLogger(OpenFeatureFactory.class);

  @Context
  @Bean(preDestroy = "shutdown")
  public OpenFeatureAPI openFeatureApi(SampleConfiguration configuration) {
    ConnectionMode mode = modeOf(configuration.getMode());
    Logger sdkLogger = LoggerFactory.getLogger("sample.configdirector");

    ConfigDirectorProvider provider =
        new ConfigDirectorProvider(
            configuration.getServerKey(),
            options ->
                options
                    .metadata("openfeature-micronaut-sample", "1.0.0")
                    .logger(sdkLogger)
                    .connection(
                        connection -> {
                          connection.mode(mode);
                          connection.timeout(configuration.getTimeout());
                          String baseUrl = configuration.getBaseUrl();
                          if (baseUrl != null && !baseUrl.isBlank()) {
                            connection.url(baseUrl);
                          }
                        }));

    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.onProviderConfigurationChanged(
        details -> log.info("Configs updated: {}", details.getFlagsChanged()));
    api.setProviderAndWait(provider);

    log.info("ConfigDirector OpenFeature provider registered once at startup (mode={})", mode);
    return api;
  }

  @Singleton
  public Client openFeatureClient(OpenFeatureAPI api) {
    return api.getClient();
  }

  private static ConnectionMode modeOf(String mode) {
    return switch (mode.toLowerCase(Locale.ROOT)) {
      case "polling" -> ConnectionMode.POLLING;
      default -> ConnectionMode.STREAMING;
    };
  }
}
