package com.configdirector.testing;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.internal.client.InMemoryConnection;
import java.util.Map;

/**
 * A real ConfigDirector client connected to an in-memory server that the test controls.
 *
 * <p>The client under {@link #client()} is the SDK's production client: value parsing, targeting
 * evaluation, watches, events, and readiness behave as they do against ConfigDirector. Only the
 * connection and telemetry are replaced, so no request is ever made and nothing is left running
 * after the client is closed.
 *
 * <p>Every operation that changes values delivers the change on the calling thread before it
 * returns, so watches and {@code configsUpdated} handlers run on the test's thread, and an
 * assertion can follow the call directly.
 */
public final class TestClient implements AutoCloseable {

  private final InMemoryConnection connection;

  TestClient(InMemoryConnection connection) {
    this.connection = connection;
  }

  /**
   * The SDK client to hand to the code under test. It starts uninitialized, like a production
   * client; {@code initialize} completes at once with the stored values unless initialization is
   * held or armed to fail.
   *
   * @return the client, which is closed by {@link #close()}
   */
  public ConfigDirectorClient client() {
    return connection.client();
  }

  /**
   * Stores {@code value} under {@code key} and, when the client is connected, delivers it as an
   * update carrying only that key, so reads, watches, and {@code configsUpdated} handlers see it.
   *
   * <p>The config type follows from the value: a {@code Boolean}, an {@code Integer} or {@code
   * Long} (an integer config), a {@code Float} or {@code Double} (a float config), a {@code
   * String}, or a {@code Map} with {@code String} keys or a {@code List} (a JSON config). Every
   * context receives the same value.
   *
   * @param key the config key, not blank
   * @param value the value to serve, not null
   * @throws com.configdirector.ConfigDirectorValidationException if the key is blank, the value is
   *     null, a non-finite number, or of an unsupported type, or JSON contents cannot be encoded
   */
  public void setValue(String key, Object value) {
    connection.setValue(key, value);
  }

  /**
   * Stores the JSON document {@code json} under {@code key} as a JSON config and, when the client
   * is connected, delivers it as an update carrying only that key.
   *
   * @param key the config key, not blank
   * @param json strict JSON text holding an object or an array
   * @throws com.configdirector.ConfigDirectorValidationException if the key is blank or the text
   *     is not a single strict JSON object or array
   */
  public void setJsonValue(String key, String json) {
    connection.setJsonValue(key, json);
  }

  /**
   * Removes {@code key} and, when the client is connected, delivers a full update without it, so
   * reads fall back to the in-code default value and watches of {@code key} receive that default.
   *
   * @param key the config key
   */
  public void removeValue(String key) {
    connection.removeValue(key);
  }

  /**
   * Replaces every stored value with {@code values}, disarms any armed hold or failure, and, when
   * the client is connected, delivers the new values as a full update. Use it to reset a test
   * client shared across tests.
   *
   * @param values the values to serve from now on, keyed by config key
   * @throws com.configdirector.ConfigDirectorValidationException as {@link #setValue(String,
   *     Object)} does, in which case nothing changes
   */
  public void replaceValues(Map<String, ?> values) {
    connection.replaceValues(values);
  }

  /**
   * Makes the next {@code initialize} wait until {@link #completeInitialization()} or {@link
   * #failInitialization()} is called, or until the client's timeout elapses. Because {@code
   * initialize} blocks, a test calls it on another thread.
   */
  public void holdInitialization() {
    connection.holdInitialization();
  }

  /**
   * Delivers the stored values to a held {@code initialize}, on the calling thread, so it
   * completes with the client ready. When a hold is armed but no {@code initialize} has started,
   * it disarms the hold instead.
   */
  public void completeInitialization() {
    connection.completeInitialization();
  }

  /**
   * Fails a held {@code initialize} with an unrecoverable connection error, or arms the next
   * {@code initialize} to fail. {@code initialize} completes promptly with the client not ready,
   * and the error is logged.
   */
  public void failInitialization() {
    connection.failInitialization();
  }

  /** Closes the client. Closing twice is harmless. */
  @Override
  public void close() {
    connection.client().close();
  }
}
