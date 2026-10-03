package com.configdirector.internal.client;

import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConnectionOptions;
import com.configdirector.internal.SdkIdentity;
import com.configdirector.internal.evaluation.Config;
import com.configdirector.internal.telemetry.DiscardingTelemetryCollector;
import com.configdirector.internal.transport.ConfigBundle;
import com.configdirector.internal.transport.ConfigDirectorConnectionException;
import com.configdirector.internal.transport.Transport;
import com.configdirector.internal.transport.Transports;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;

public final class InMemoryConnection {

  private static final String TEST_CLIENT_SDK_KEY = "test-client";
  private static final String ENVIRONMENT_ID = "test-environment";
  private static final String PROJECT_ID = "test-project";
  private static final int FAILED_STATUS = 401;

  private enum Armed {
    NOTHING,
    HOLD,
    FAILURE
  }

  private enum Outcome {
    COMPLETED,
    FAILED,
    ENDED
  }

  private final Logger logger;
  private final ConfigDirectorClient client;
  private Consumer<ConfigBundle> onBundle;

  private final Object stateLock = new Object();
  private final Map<String, Config> configs = new LinkedHashMap<>();
  private Armed armed = Armed.NOTHING;
  private HeldAttempt heldAttempt;
  private boolean connected;

  private final Object deliveryLock = new Object();
  private final Deque<ConfigBundle> pendingDeliveries = new ArrayDeque<>();
  private boolean delivering;

  public InMemoryConnection(Map<String, ?> values, Duration timeout, Logger logger) {
    this.logger = Objects.requireNonNull(logger, "logger");
    configs.putAll(encodeAll(values));
    ConnectionOptions connection =
        timeout == null
            ? ConnectionOptions.defaults()
            : ConnectionOptions.builder().timeout(timeout).build();
    this.client =
        new DefaultConfigDirectorClient(
            TEST_CLIENT_SDK_KEY,
            SdkIdentity.SERVER_SDK,
            null,
            connection,
            null,
            logger,
            (mode, options) -> {
              onBundle = options.onBundle();
              return new Attempts();
            },
            options -> DiscardingTelemetryCollector.INSTANCE);
  }

  public ConfigDirectorClient client() {
    return client;
  }

  public void setValue(String key, Object value) {
    store(key, TestValueEncoder.encode(key, value));
  }

  public void setJsonValue(String key, String json) {
    store(key, TestValueEncoder.encodeJsonText(key, json));
  }

  public void removeValue(String key) {
    Objects.requireNonNull(key, "key");
    ConfigBundle update;
    synchronized (stateLock) {
      configs.remove(key);
      update = connected ? fullUpdate() : null;
    }
    deliverIfAny(update);
  }

  public void replaceValues(Map<String, ?> values) {
    Map<String, Config> encoded = encodeAll(values);
    ConfigBundle update;
    synchronized (stateLock) {
      configs.clear();
      configs.putAll(encoded);
      armed = Armed.NOTHING;
      update = connected ? fullUpdate() : null;
    }
    deliverIfAny(update);
  }

  public void holdInitialization() {
    synchronized (stateLock) {
      armed = Armed.HOLD;
    }
  }

  public void completeInitialization() {
    HeldAttempt attempt;
    ConfigBundle update = null;
    synchronized (stateLock) {
      attempt = heldAttempt;
      heldAttempt = null;
      if (attempt != null) {
        connected = true;
        update = fullUpdate();
      } else if (armed == Armed.HOLD) {
        armed = Armed.NOTHING;
      }
    }
    if (attempt != null) {
      deliver(update);
      attempt.settle(Outcome.COMPLETED);
    }
  }

  public void failInitialization() {
    HeldAttempt attempt;
    synchronized (stateLock) {
      attempt = heldAttempt;
      heldAttempt = null;
      if (attempt == null) {
        armed = Armed.FAILURE;
      }
    }
    if (attempt != null) {
      attempt.settle(Outcome.FAILED);
    }
  }

  private void store(String key, Config definition) {
    ConfigBundle update;
    synchronized (stateLock) {
      configs.put(key, definition);
      update = connected ? deltaUpdate(key, definition) : null;
    }
    deliverIfAny(update);
  }

  private void connect(Duration timeout) {
    HeldAttempt previous;
    HeldAttempt attempt = null;
    Armed pickedUp;
    synchronized (stateLock) {
      connected = false;
      previous = heldAttempt;
      heldAttempt = null;
      pickedUp = armed;
      armed = Armed.NOTHING;
      if (pickedUp == Armed.HOLD) {
        attempt = new HeldAttempt();
        heldAttempt = attempt;
      }
    }
    if (previous != null) {
      previous.settle(Outcome.ENDED);
    }
    switch (pickedUp) {
      case FAILURE -> throw fatalError();
      case HOLD -> awaitHeld(attempt, timeout);
      case NOTHING -> connectNow();
    }
  }

  private void connectNow() {
    ConfigBundle update;
    synchronized (stateLock) {
      connected = true;
      update = fullUpdate();
    }
    deliver(update);
  }

  private void awaitHeld(HeldAttempt attempt, Duration timeout) {
    if (!attempt.awaitRelease(timeout)) {
      synchronized (stateLock) {
        if (heldAttempt == attempt) {
          heldAttempt = null;
          attempt.settle(Outcome.ENDED);
        }
      }
      attempt.awaitRelease();
    }
    if (attempt.outcome() == Outcome.FAILED) {
      throw fatalError();
    }
  }

  private void closeTransport() {
    HeldAttempt attempt;
    synchronized (stateLock) {
      connected = false;
      attempt = heldAttempt;
      heldAttempt = null;
    }
    if (attempt != null) {
      attempt.settle(Outcome.ENDED);
    }
  }

  private boolean isConnected() {
    synchronized (stateLock) {
      return connected;
    }
  }

  private ConfigDirectorConnectionException fatalError() {
    ConfigDirectorConnectionException error =
        Transports.fatalStatusError(FAILED_STATUS, "the test client failed this initialization");
    logger.error("[InMemoryConnection] {}", error.getMessage());
    return error;
  }

  private static ConfigBundle deltaUpdate(String key, Config definition) {
    return new ConfigBundle(
        Map.of(key, definition),
        Map.of(),
        ConfigBundle.BundleKind.DELTA,
        ENVIRONMENT_ID,
        PROJECT_ID,
        null);
  }

  private ConfigBundle fullUpdate() {
    return new ConfigBundle(
        configs, Map.of(), ConfigBundle.BundleKind.FULL, ENVIRONMENT_ID, PROJECT_ID, null);
  }

  private void deliverIfAny(ConfigBundle update) {
    if (update != null) {
      deliver(update);
    }
  }

  private void deliver(ConfigBundle update) {
    synchronized (deliveryLock) {
      pendingDeliveries.addLast(update);
      if (delivering) {
        return;
      }
      delivering = true;
    }
    boolean drained = false;
    try {
      for (ConfigBundle next = nextDelivery(); next != null; next = nextDelivery()) {
        onBundle.accept(next);
      }
      drained = true;
    } finally {
      if (!drained) {
        abandonDeliveries();
      }
    }
  }

  private void abandonDeliveries() {
    synchronized (deliveryLock) {
      delivering = false;
      pendingDeliveries.clear();
    }
  }

  private ConfigBundle nextDelivery() {
    synchronized (deliveryLock) {
      ConfigBundle next = pendingDeliveries.pollFirst();
      if (next == null) {
        delivering = false;
      }
      return next;
    }
  }

  private static Map<String, Config> encodeAll(Map<String, ?> values) {
    Objects.requireNonNull(values, "values");
    Map<String, Config> encoded = new LinkedHashMap<>();
    for (Map.Entry<String, ?> entry : values.entrySet()) {
      encoded.put(entry.getKey(), TestValueEncoder.encode(entry.getKey(), entry.getValue()));
    }
    return encoded;
  }

  private final class Attempts implements Transport {

    @Override
    public void connect(Duration timeout) {
      InMemoryConnection.this.connect(timeout);
    }

    @Override
    public boolean isConnected() {
      return InMemoryConnection.this.isConnected();
    }

    @Override
    public void close(Duration timeout) {
      closeTransport();
    }
  }

  private static final class HeldAttempt {

    private final CountDownLatch released = new CountDownLatch(1);
    private volatile Outcome outcome = Outcome.ENDED;

    void settle(Outcome outcome) {
      this.outcome = outcome;
      released.countDown();
    }

    Outcome outcome() {
      return outcome;
    }

    boolean awaitRelease(Duration timeout) {
      try {
        return released.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    void awaitRelease() {
      boolean interrupted = Thread.interrupted();
      try {
        while (true) {
          try {
            released.await();
            return;
          } catch (InterruptedException again) {
            interrupted = true;
          }
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}
