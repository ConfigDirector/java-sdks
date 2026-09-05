package com.configdirector.samples.springboot;

import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConnectionMode;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the one ConfigDirector client this application uses.
 *
 * <p><b>The client is a singleton.</b> Create it once at startup, share it for the lifetime of the
 * process, and close it on shutdown. A Spring {@code @Bean} is exactly that: the container builds
 * it once and injects the same instance everywhere.
 *
 * <p>Why it matters:
 *
 * <ul>
 *   <li><b>Each client holds its own connection.</b> While streaming it keeps a long-lived
 *       connection open for config updates; a client per request would open and abandon one every
 *       time.
 *   <li><b>Initialization does network I/O.</b> {@code initialize()} blocks until the first config
 *       state arrives. Paying that per request would add latency to every response.
 *   <li><b>A fresh client is never ready.</b> Until its first config state arrives every config
 *       resolves to the default, so per-request clients would serve defaults more or less forever.
 *   <li><b>Watches and event handlers live on the instance.</b> They fire only while the client
 *       they were registered on is alive.
 * </ul>
 *
 * <p>So never build a client inside a controller. Concurrency is not a reason to make more of
 * them: the client is thread safe, so every request thread shares this one safely.
 */
@Configuration
@EnableConfigurationProperties(SampleProperties.class)
public class ConfigDirectorConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ConfigDirectorConfiguration.class);

  /**
   * {@code destroyMethod} is what closes the client on shutdown, dropping its connections. Spring
   * calls it when the context stops, which covers Ctrl-C, a container SIGTERM, and a failed
   * startup alike.
   */
  @Bean(destroyMethod = "close")
  public ConfigDirectorClient configDirectorClient(SampleProperties properties) {
    // The logger handed to the client. Left to itself the SDK logs to "com.configdirector";
    // passing one in puts its output under this application's own logging namespace instead,
    // where existing appenders and level configuration already apply.
    Logger sdkLogger = LoggerFactory.getLogger("sample.configdirector");

    // Building the client makes no network calls.
    ConfigDirectorClient client =
        ConfigDirector.client(
            properties.getServerKey(),
            options ->
                options
                    .metadata("spring-boot-sample", "1.0.0")
                    .logger(sdkLogger)
                    .connection(
                        connection -> {
                          connection.mode(modeOf(properties.getMode()));
                          connection.timeout(properties.getTimeout());
                          if (properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()) {
                            connection.url(properties.getBaseUrl());
                          }
                        }));

    // Blocks until the first config state arrives or the timeout elapses. It never throws on a
    // connection failure, so check isReady() to find out whether state actually arrived.
    client.initialize();

    log.info("ConfigDirector client created once at startup (ready={})", client.isReady());
    if (!client.isReady()) {
      log.warn("ConfigDirector is not ready, every config will resolve to its default");
    }
    return client;
  }

  private static ConnectionMode modeOf(String mode) {
    return switch (mode.toLowerCase(Locale.ROOT)) {
      case "polling" -> ConnectionMode.POLLING;
      default -> ConnectionMode.STREAMING;
    };
  }
}
