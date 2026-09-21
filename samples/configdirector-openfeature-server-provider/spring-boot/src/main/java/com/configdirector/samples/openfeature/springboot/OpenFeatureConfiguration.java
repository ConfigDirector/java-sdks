package com.configdirector.samples.openfeature.springboot;

import com.configdirector.ConnectionMode;
import com.configdirector.openfeature.ConfigDirectorProvider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SampleProperties.class)
public class OpenFeatureConfiguration {

  private static final Logger log = LoggerFactory.getLogger(OpenFeatureConfiguration.class);

  @Bean(destroyMethod = "shutdown")
  public OpenFeatureAPI openFeatureApi(SampleProperties properties) {
    Logger sdkLogger = LoggerFactory.getLogger("sample.configdirector");

    ConfigDirectorProvider provider =
        new ConfigDirectorProvider(
            properties.getServerKey(),
            options ->
                options
                    .metadata("openfeature-spring-boot-sample", "1.0.0")
                    .logger(sdkLogger)
                    .connection(
                        connection -> {
                          connection.mode(modeOf(properties.getMode()));
                          connection.timeout(properties.getTimeout());
                          if (properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()) {
                            connection.url(properties.getBaseUrl());
                          }
                        }));

    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.onProviderConfigurationChanged(
        details -> log.info("Configs updated: {}", details.getFlagsChanged()));
    api.setProviderAndWait(provider);

    log.info("ConfigDirector OpenFeature provider registered once at startup");
    return api;
  }

  @Bean
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
