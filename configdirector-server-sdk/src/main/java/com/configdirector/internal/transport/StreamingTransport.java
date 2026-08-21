package com.configdirector.internal.transport;

import com.configdirector.internal.eventsource.EventSourceClient;
import com.configdirector.internal.eventsource.EventSourceMessage;
import com.configdirector.internal.eventsource.ReadyState;
import com.configdirector.internal.eventsource.ReconnectionState;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

public final class StreamingTransport implements Transport {

  private static final String PATH = "server/sse/v1";

  // 2^9 = 512 seconds, which caps the backoff just under 10 minutes.
  private static final int MAX_BACKOFF_EXPONENT = 9;

  // Past this many attempts a reconnect is no longer routine and deserves a louder log level.
  private static final int QUIET_ATTEMPTS = 5;

  private final TransportOptions options;
  private final Logger logger;
  private final String url;

  private final AtomicReference<EventSourceClient> client = new AtomicReference<>();
  private final AtomicReference<ConfigDirectorConnectionException> fatalError = new AtomicReference<>();
  private volatile CountDownLatch settled = new CountDownLatch(1);

  public StreamingTransport(TransportOptions options) {
    this.options = options;
    this.logger = options.logger();
    this.url = Transports.resolve(options.baseUrl(), PATH);
  }

  @Override
  public void connect(Duration timeout) {
    close();
    settled = new CountDownLatch(1);
    fatalError.set(null);

    EventSourceClient stream =
        EventSourceClient.builder(url)
            .method("POST")
            .headers(Transports.REQUEST_HEADERS)
            .body(Transports.jsonBody(Transports.requestPayload(options, null)))
            .logger(logger)
            .onConnect(this::onConnect)
            .onDisconnect(() -> logger.debug("[StreamingTransport] Disconnected"))
            .onMessage(this::onMessage)
            .shouldReconnect(this::shouldReconnect)
            .calculateReconnectDelay(this::reconnectDelay)
            .build();
    client.set(stream);
    stream.connect();

    // Returning on the timeout is not a failure: the stream keeps retrying in the background, and
    // the client reports itself unready until config state arrives.
    awaitSettled(timeout);

    ConfigDirectorConnectionException fatal = fatalError.get();
    if (fatal != null) {
      throw fatal;
    }
  }

  @Override
  public boolean isConnected() {
    EventSourceClient stream = client.get();
    return stream != null && stream.readyState() == ReadyState.OPEN;
  }

  @Override
  public void close() {
    EventSourceClient stream = client.getAndSet(null);
    if (stream != null) {
      stream.close();
    }
  }

  private void awaitSettled(Duration timeout) {
    try {
      settled.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void onConnect() {
    logger.debug("[StreamingTransport] Connected");
    settled.countDown();
  }

  private void onMessage(EventSourceMessage message) {
    ConfigBundle bundle;
    try {
      bundle = BundleParser.parse(message.data(), logger);
    } catch (RuntimeException error) {
      logger.error("[StreamingTransport] Error parsing a config update", error);
      return;
    }
    options.onBundle().accept(bundle);
  }

  private boolean shouldReconnect(ReconnectionState state) {
    if (!Transports.isFatalStatus(state.status())) {
      return true;
    }
    ConfigDirectorConnectionException fatal =
        Transports.fatalStatusError(
            state.status(), state.error() == null ? null : state.error().getMessage());
    fatalError.set(fatal);
    logger.error("[StreamingTransport] {}", fatal.getMessage());
    // Whoever is still blocked in connect() is waiting for exactly this.
    settled.countDown();
    return false;
  }

  private Duration reconnectDelay(ReconnectionState state) {
    Duration delay = Duration.ofSeconds(1L << Math.min(state.attempt(), MAX_BACKOFF_EXPONENT));
    if (state.attempt() <= QUIET_ATTEMPTS) {
      logger.info(
          "[StreamingTransport] Scheduling reconnect attempt #{} in {}.", state.attempt(), delay);
    } else {
      logger.warn(
          "[StreamingTransport] Scheduling reconnect attempt #{} in {}.", state.attempt(), delay);
    }
    return delay;
  }
}
