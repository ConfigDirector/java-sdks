package com.configdirector.internal.transport;

import com.configdirector.internal.eventsource.EventSourceClient;
import com.configdirector.internal.eventsource.EventSourceMessage;
import com.configdirector.internal.eventsource.ReadyState;
import com.configdirector.internal.eventsource.ReconnectionState;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

public final class StreamingTransport implements Transport {

  private static final String PATH = "server/sse/v1";
  private static final String HEARTBEAT_PATH = "server/heartbeat/v1";

  // Fixed by the protocol rather than configurable: the dashboard decides a streaming session has
  // died by how long ago its last heartbeat arrived, so every SDK has to beat on the same interval.
  private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(90);

  // 2^9 = 512 seconds, which caps the backoff just under 10 minutes.
  private static final int MAX_BACKOFF_EXPONENT = 9;

  // How much of each delay is fixed; the rest is drawn at random. Half and half keeps the delay
  // growing with every attempt while spreading a fleet that all lost the stream at the same
  // moment.
  private static final double FIXED_SHARE = 0.5;

  // Past this many attempts a reconnect is no longer routine and deserves a louder log level.
  private static final int QUIET_ATTEMPTS = 5;

  // The server sends a keepalive comment every 15 seconds, so three missed in a row means a dead
  // connection rather than a quiet one. Duration.ZERO here would wait forever.
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(45);

  private final TransportOptions options;
  private final Logger logger;
  private final String url;
  private final String heartbeatUrl;

  private final Duration readTimeout;
  private final Duration heartbeatInterval;

  private final AtomicReference<EventSourceClient> client = new AtomicReference<>();
  private final AtomicReference<ConfigDirectorConnectionException> fatalError = new AtomicReference<>();
  private final AtomicReference<String> sessionId = new AtomicReference<>();
  private final AtomicReference<ScheduledExecutorService> heartbeat = new AtomicReference<>();
  private volatile CountDownLatch settled = new CountDownLatch(1);

  public StreamingTransport(TransportOptions options) {
    this(options, READ_TIMEOUT, HEARTBEAT_INTERVAL);
  }

  // Package private so a test can stall a stream out in milliseconds rather than in minutes.
  StreamingTransport(TransportOptions options, Duration readTimeout) {
    this(options, readTimeout, HEARTBEAT_INTERVAL);
  }

  // Package private for the same reason; the public API offers no way to change the intervals.
  StreamingTransport(TransportOptions options, Duration readTimeout, Duration heartbeatInterval) {
    this.options = options;
    this.logger = options.logger();
    this.url = Transports.resolve(options.baseUrl(), PATH);
    this.heartbeatUrl = Transports.resolve(options.baseUrl(), HEARTBEAT_PATH);
    this.readTimeout = readTimeout;
    this.heartbeatInterval = heartbeatInterval;
  }

  // Package private so the default can be checked without waiting one out.
  Duration readTimeout() {
    return readTimeout;
  }

  // Package private so the default can be checked without waiting one out.
  Duration heartbeatInterval() {
    return heartbeatInterval;
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
            .body(this::buildRequestBody)
            .logger(logger)
            .readTimeout(readTimeout)
            .onConnect(this::onConnect)
            .onDisconnect(() -> logger.debug("[StreamingTransport] Disconnected"))
            .onError(error -> logger.debug("[StreamingTransport] The stream failed", error))
            .onMessage(this::onMessage)
            .shouldReconnect(this::shouldReconnect)
            .calculateReconnectDelay(this::reconnectDelay)
            .build();
    client.set(stream);
    stream.connect();
    startHeartbeat();

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
  public void close(Duration timeout) {
    ScheduledExecutorService beating = heartbeat.getAndSet(null);
    if (beating != null) {
      beating.shutdownNow();
    }
    EventSourceClient stream = client.getAndSet(null);
    if (stream != null) {
      stream.close(timeout);
    }
  }

  // The scheduled future carries nothing: sendHeartbeat handles its own failures, and the task is
  // cancelled by shutting its executor down.
  @SuppressWarnings("FutureReturnValueIgnored")
  private void startHeartbeat() {
    ScheduledExecutorService beating =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "configdirector-heartbeat");
              thread.setDaemon(true);
              return thread;
            });
    beating.scheduleAtFixedRate(
        this::sendHeartbeat,
        heartbeatInterval.toNanos(),
        heartbeatInterval.toNanos(),
        TimeUnit.NANOSECONDS);
    ScheduledExecutorService previous = heartbeat.getAndSet(beating);
    if (previous != null) {
      previous.shutdownNow();
    }
  }

  private void sendHeartbeat() {
    String session = sessionId.get();
    if (!isConnected() || session == null) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("serverSdkKey", options.serverSdkKey());
    payload.put("sessionId", session);
    try {
      options
          .http()
          .post(heartbeatUrl, Transports.jsonBody(payload), Transports.REQUEST_HEADERS, heartbeatInterval);
    } catch (RuntimeException error) {
      // A missed heartbeat is not worth disturbing the stream over; the server tolerates gaps,
      // and a connection problem shows up on the stream itself soon enough.
      logger.debug("[StreamingTransport] The heartbeat failed", error);
    }
  }

  String sessionId() {
    return sessionId.get();
  }

  private byte[] buildRequestBody() {
    String id = UUID.randomUUID().toString();
    sessionId.set(id);
    return Transports.jsonBody(Transports.requestPayload(options, null, id));
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
    } catch (NotAConfigBundleException notABundle) {
      // A heartbeat, or any other frame the stream carries alongside config updates. Skipped
      // rather than applied: an empty bundle would read as a full one and clear config state.
      logger.debug("[StreamingTransport] Ignoring a '{}' event", message.type());
      return;
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

  // Package private so the distribution can be sampled without a stream behind it.
  static Duration backoffDelay(int attempt) {
    int exponent = Math.min(Math.max(attempt, 1), MAX_BACKOFF_EXPONENT);
    long ceiling = TimeUnit.SECONDS.toMillis(1L << exponent);
    long fixed = (long) (ceiling * FIXED_SHARE);
    return Duration.ofMillis(fixed + ThreadLocalRandom.current().nextLong(ceiling - fixed));
  }

  private Duration reconnectDelay(ReconnectionState state) {
    Duration delay = backoffDelay(state.attempt());
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
