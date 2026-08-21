package com.configdirector;

import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settings for a client, handed to the lambda {@link ConfigDirector#client(String, Consumer)}
 * takes. Every setter returns this, so calls chain.
 *
 * <p>Read once when the client is built; changing it afterwards has no effect.
 */
public final class ClientOptions {

  private Metadata metadata = Metadata.empty();
  private ConnectionOptions connection = ConnectionOptions.defaults();
  private Logger logger = LoggerFactory.getLogger(ConfigDirector.LOGGER_NAME);

  ClientOptions() {}

  /** Supplying appName and appVersion lets targeting rules reference them. */
  public ClientOptions metadata(Metadata metadata) {
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    return this;
  }

  /** Shorthand for {@link #metadata(Metadata)} with both fields. */
  public ClientOptions metadata(String appName, String appVersion) {
    return metadata(new Metadata(appName, appVersion));
  }

  public ClientOptions connection(Consumer<ConnectionOptions.Builder> configure) {
    Objects.requireNonNull(configure, "configure");
    ConnectionOptions.Builder builder = ConnectionOptions.builder();
    configure.accept(builder);
    this.connection = builder.build();
    return this;
  }

  /** For settings built once and shared by several clients. */
  public ClientOptions connection(ConnectionOptions connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
    return this;
  }

  /** Defaults to the SLF4J logger named {@value ConfigDirector#LOGGER_NAME}. */
  public ClientOptions logger(Logger logger) {
    this.logger = Objects.requireNonNull(logger, "logger");
    return this;
  }

  Metadata metadata() {
    return metadata;
  }

  ConnectionOptions connection() {
    return connection;
  }

  Logger logger() {
    return logger;
  }
}
