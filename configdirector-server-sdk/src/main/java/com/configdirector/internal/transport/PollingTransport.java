package com.configdirector.internal.transport;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

public class PollingTransport implements Transport {

  private static final String PATH = "server/polling/v1";

  // The server has nothing newer than the timestamp that was sent.
  private static final int HTTP_NO_CONTENT = 204;

  private final TransportOptions options;
  private final Logger logger;
  private final String url;
  private final Duration interval;

  private final Object lock = new Object();
  private final AtomicBoolean fatal = new AtomicBoolean();
  private final AtomicReference<String> lastUpdateTimestamp = new AtomicReference<>();
  private final String sessionId = UUID.randomUUID().toString();

  private CountDownLatch stop = new CountDownLatch(0);
  private Thread poller;

  // The first fetch happens before the poller starts, so without this a close() landing in that
  // gap finds no thread to stop and the fetch then starts one nothing will stop.
  private boolean closed;

  public PollingTransport(TransportOptions options) {
    this.options = options;
    this.logger = options.logger();
    this.url = Transports.resolve(options.baseUrl(), PATH);
    this.interval = options.pollingInterval().isNegative() ? Duration.ZERO : options.pollingInterval();
  }

  @Override
  public void connect(Duration timeout) {
    synchronized (lock) {
      closed = false;
    }
    try {
      fetch(timeout);
    } finally {
      // A transient failure on the first fetch must not leave the SDK without a connection, so
      // polling starts either way. An unrecoverable one has already closed the transport.
      if (!fatal.get()) {
        startPolling(timeout);
      }
    }
  }

  @Override
  public boolean isConnected() {
    Thread thread;
    synchronized (lock) {
      thread = poller;
    }
    return thread != null && thread.isAlive();
  }

  // Identity is the question being asked: whether this is the very same thread, not an equal one.
  @SuppressWarnings("ReferenceEquality")
  @Override
  public void close(Duration timeout) {
    Thread thread;
    CountDownLatch signal;
    synchronized (lock) {
      closed = true;
      thread = poller;
      poller = null;
      signal = stop;
    }
    signal.countDown();
    // Joining from the polling thread itself would deadlock.
    if (thread == null || thread == Thread.currentThread()) {
      return;
    }
    // Guarded because join(0) waits forever, and a spent budget means the opposite.
    long millis = timeout.toMillis();
    if (millis <= 0L) {
      return;
    }
    try {
      thread.join(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  String sessionId() {
    return sessionId;
  }

  private void startPolling(Duration timeout) {
    if (interval.isZero()) {
      return;
    }
    synchronized (lock) {
      if (closed || poller != null) {
        return;
      }
      CountDownLatch signal = new CountDownLatch(1);
      stop = signal;
      poller = new Thread(() -> poll(timeout, signal), "configdirector-polling");
      poller.setDaemon(true);
      poller.start();
    }
  }

  private void poll(Duration timeout, CountDownLatch signal) {
    while (!awaitInterval(signal)) {
      try {
        fetch(timeout);
      } catch (ConfigDirectorConnectionException error) {
        logger.error("[PollingTransport] Error during polling", error);
      } catch (RuntimeException error) {
        // The polling thread must not die without saying why.
        logger.error("[PollingTransport] Polling stopped unexpectedly", error);
        return;
      }
      if (fatal.get()) {
        return;
      }
    }
  }

  private boolean awaitInterval(CountDownLatch signal) {
    try {
      return signal.await(interval.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return true;
    }
  }

  private void fetch(Duration timeout) {
    if (fatal.get()) {
      logger.warn(
          "[PollingTransport] There was a prior unrecoverable error. Ignoring attempt to reconnect.");
      return;
    }

    HttpResponse response = post(timeout);
    if (!response.ok()) {
      if (Transports.isFatalStatus(response.status())) {
        throw failFatally(Transports.fatalStatusError(response.status(), response.body()));
      }
      throw new ConfigDirectorConnectionException(
          "Connection failed with status: " + response.status(), response.status());
    }

    if (response.status() == HTTP_NO_CONTENT) {
      return;
    }

    ConfigBundle bundle;
    try {
      bundle = BundleParser.parse(response.body(), logger);
    } catch (BundleFormatException error) {
      throw new ConfigDirectorConnectionException(
          "Failed to parse the response from the server: " + error.getMessage(), null, error);
    }

    if (bundle.timestamp() != null) {
      lastUpdateTimestamp.set(bundle.timestamp());
    }
    options.onBundle().accept(bundle);
  }

  private HttpResponse post(Duration timeout) {
    byte[] body =
        Transports.jsonBody(Transports.requestPayload(options, lastUpdateTimestamp.get(), sessionId));
    try {
      // Network-level failures -- refused, unresolved, timed out -- arrive as
      // ConfigDirectorConnectionException and are left to propagate: all are worth retrying.
      return options.http().post(url, body, Transports.REQUEST_HEADERS, timeout);
    } catch (UnusableUrlException error) {
      throw failFatally(
          new ConfigDirectorConnectionException(
              "Connection failed with an unusable URL '"
                  + url
                  + "': "
                  + error.getMessage()
                  + ". This is an unrecoverable error, retry attempts will be ignored.",
              null,
              error));
    }
  }

  // Returns rather than throws so a value-returning caller can `throw failFatally(...)` and the
  // compiler can see that path terminate.
  private ConfigDirectorConnectionException failFatally(ConfigDirectorConnectionException error) {
    fatal.set(true);
    close();
    return error;
  }
}
