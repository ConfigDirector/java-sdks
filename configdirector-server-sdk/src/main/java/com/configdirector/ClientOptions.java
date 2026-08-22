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
  private TelemetryOptions telemetry = TelemetryOptions.defaults();
  private Logger logger = LoggerFactory.getLogger(ConfigDirector.LOGGER_NAME);

  ClientOptions() {}

  /**
   * Describes the calling application. Supplying appName and appVersion lets targeting rules
   * reference them.
   *
   * @param metadata the application's name and version
   * @return these options, so calls chain
   */
  public ClientOptions metadata(Metadata metadata) {
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    return this;
  }

  /**
   * Shorthand for {@link #metadata(Metadata)} with both fields.
   *
   * @param appName the application's name
   * @param appVersion the version it is running
   * @return these options, so calls chain
   */
  public ClientOptions metadata(String appName, String appVersion) {
    return metadata(new Metadata(appName, appVersion));
  }

  /**
   * Adjusts how the client connects.
   *
   * @param configure receives a builder holding the connection defaults
   * @return these options, so calls chain
   */
  public ClientOptions connection(Consumer<ConnectionOptions.Builder> configure) {
    Objects.requireNonNull(configure, "configure");
    ConnectionOptions.Builder builder = ConnectionOptions.builder();
    configure.accept(builder);
    this.connection = builder.build();
    return this;
  }

  /**
   * Uses connection settings built once and shared by several clients.
   *
   * @param connection the settings to connect with
   * @return these options, so calls chain
   */
  public ClientOptions connection(ConnectionOptions connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
    return this;
  }

  /**
   * Tunes what the SDK reports back about the configs it evaluated.
   *
   * @param configure receives a builder holding the telemetry defaults
   * @return these options, so calls chain
   */
  public ClientOptions telemetry(Consumer<TelemetryOptions.Builder> configure) {
    Objects.requireNonNull(configure, "configure");
    TelemetryOptions.Builder builder = TelemetryOptions.builder();
    configure.accept(builder);
    this.telemetry = builder.build();
    return this;
  }

  /**
   * Uses telemetry settings built once and shared by several clients.
   *
   * @param telemetry the settings to collect and report with
   * @return these options, so calls chain
   */
  public ClientOptions telemetry(TelemetryOptions telemetry) {
    this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    return this;
  }

  /**
   * Where the SDK writes. Defaults to the SLF4J logger named {@value ConfigDirector#LOGGER_NAME}.
   *
   * @param logger the logger to write to
   * @return these options, so calls chain
   */
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

  TelemetryOptions telemetry() {
    return telemetry;
  }

  Logger logger() {
    return logger;
  }
}
