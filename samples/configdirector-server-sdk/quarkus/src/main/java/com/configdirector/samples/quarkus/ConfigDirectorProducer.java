package com.configdirector.samples.quarkus;

import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConnectionMode;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the one ConfigDirector client this application uses.
 *
 * <p><b>The client is a singleton.</b> Create it once at startup, share it for the lifetime of the
 * process, and close it on shutdown. A CDI producer method is exactly that: the container calls it
 * once and injects the same instance everywhere.
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
 * <p>So never build a client inside a resource method. Concurrency is not a reason to make more of
 * them: the client is thread safe, so every request thread shares this one safely.
 */
@ApplicationScoped
public class ConfigDirectorProducer {

  private static final Logger log = LoggerFactory.getLogger(ConfigDirectorProducer.class);

  /**
   * {@code @Singleton} rather than {@code @ApplicationScoped}, so injection points receive the
   * client itself rather than a CDI client proxy that forwards to it. Nothing here needs the
   * indirection, and a direct reference keeps the SDK's own thread safety the only thing between
   * a request thread and the client.
   *
   * <p>{@code @Startup} is the other half. Without it the bean is created on first injection --
   * which here would be the first request that needs a config, made to pay for
   * {@link ConfigDirectorClient#initialize()}. {@code @Startup} moves that to application startup,
   * where the cost belongs.
   */
  @Produces
  @Singleton
  @Startup
  public ConfigDirectorClient configDirectorClient(SampleConfig config) {
    ConnectionMode mode = modeOf(config.mode());

    // The logger handed to the client. Left to itself the SDK logs to "com.configdirector";
    // passing one in puts its output under this application's own logging namespace instead,
    // where existing appenders and level configuration already apply. Quarkus routes SLF4J into
    // JBoss Log Manager, so this needs no bridge of its own.
    Logger sdkLogger = LoggerFactory.getLogger("sample.configdirector");

    // Building the client makes no network calls.
    ConfigDirectorClient client =
        ConfigDirector.client(
            config.serverKey(),
            options ->
                options
                    .metadata("quarkus-sample", "1.0.0")
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

    // Blocks until the first config state arrives or the timeout elapses. It never throws on a
    // connection failure, so check isReady() to find out whether state actually arrived.
    client.initialize();

    log.info(
        "ConfigDirector client created once at startup (mode={}, ready={})", mode, client.isReady());
    if (!client.isReady()) {
      log.warn("ConfigDirector is not ready, every config will resolve to its default");
    }
    return client;
  }

  /**
   * The disposer, which closes the client on shutdown and drops its connections. CDI calls it when
   * the container stops, which covers Ctrl-C, a container SIGTERM, and a failed startup alike --
   * the counterpart to {@code destroyMethod} in Spring or {@code preDestroy} in Micronaut.
   */
  public void close(@Disposes ConfigDirectorClient client) {
    client.close();
  }

  private static ConnectionMode modeOf(String mode) {
    return switch (mode.toLowerCase(Locale.ROOT)) {
      case "polling" -> ConnectionMode.POLLING;
      case "one-time", "onetime" -> ConnectionMode.ONE_TIME;
      default -> ConnectionMode.STREAMING;
    };
  }
}
