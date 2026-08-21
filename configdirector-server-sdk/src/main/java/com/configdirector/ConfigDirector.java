package com.configdirector;

import com.configdirector.internal.client.DefaultConfigDirectorClient;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Entry point to the SDK.
 *
 * <p>Building a client makes no network calls; call {@link ConfigDirectorClient#initialize()} to
 * connect. One client per application is enough — it is safe to share across threads, and holds a
 * connection pool that {@link ConfigDirectorClient#close()} releases.
 *
 * <pre>{@code
 * ConfigDirectorClient client = ConfigDirector.client(sdkKey, options -> options
 *     .metadata("checkout", "1.2.3")
 *     .connection(connection -> connection
 *         .mode(ConnectionMode.POLLING)
 *         .pollingInterval(Duration.ofSeconds(30))));
 *
 * client.initialize();
 * boolean enabled = client.getBoolean("new-checkout", false);
 * }</pre>
 */
public final class ConfigDirector {

  /** The logger the SDK writes to unless one is supplied. */
  public static final String LOGGER_NAME = "com.configdirector";

  private ConfigDirector() {}

  /**
   * A client with default settings: streaming, a three-second initialization timeout, and the
   * SLF4J logger named {@value #LOGGER_NAME}.
   *
   * @param serverSdkKey a secret; do not commit it to source control
   */
  public static ConfigDirectorClient client(String serverSdkKey) {
    return client(serverSdkKey, options -> {});
  }

  /**
   * A client with the settings {@code configure} adjusts.
   *
   * @param serverSdkKey a secret; do not commit it to source control
   * @param configure receives the settings to adjust before the client is built
   */
  public static ConfigDirectorClient client(String serverSdkKey, Consumer<ClientOptions> configure) {
    Objects.requireNonNull(configure, "configure");
    ClientOptions options = new ClientOptions();
    configure.accept(options);
    return new DefaultConfigDirectorClient(
        serverSdkKey, options.metadata(), options.connection(), options.logger());
  }
}
