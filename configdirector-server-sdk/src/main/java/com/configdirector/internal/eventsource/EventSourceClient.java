package com.configdirector.internal.eventsource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Keeps an SSE stream open, reconnecting when it drops. Handlers run on the reader thread, so one
// that blocks stalls delivery. connect() and close() are safe from any thread, including from
// inside a handler.
public final class EventSourceClient implements AutoCloseable {

  private static final int READ_SIZE = 1 << 13;
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_SERVER_DELAY = Duration.ofSeconds(2);
  private static final Duration MIN_RECONNECT_DELAY = Duration.ofMillis(1);
  private static final Duration MAX_RECONNECT_DELAY = Duration.ofHours(1);
  private static final int HTTP_NO_CONTENT = 204;
  private static final int HTTP_BAD_REQUEST = 400;

  private final String url;
  private final String method;
  private final byte[] body;
  private final Map<String, String> headers;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final boolean followRedirects;
  private final int maxLineChars;
  private final int maxEventChars;
  private final Logger logger;

  private final StreamOpener opener;

  // A supplied opener belongs to whoever supplied it, and may outlive this client.
  private final boolean ownsOpener;

  private final Runnable onConnect;
  private final Runnable onDisconnect;
  private final Consumer<EventSourceMessage> onMessage;
  private final Consumer<String> onComment;
  private final Consumer<Throwable> onError;
  private final Predicate<ReconnectionState> shouldReconnect;
  private final Function<ReconnectionState, Duration> calculateReconnectDelay;

  // Guards the compound parts of connect and close against each other, and publishes the response
  // the reader is parked on. Everything else is a single volatile read or write.
  private final Object lock = new Object();

  private volatile ReadyState readyState = ReadyState.CLOSED;
  private volatile String lastEventId;
  private volatile Duration serverDelay = DEFAULT_SERVER_DELAY;

  private StopSignal stop = StopSignal.alreadyStopped();
  private Thread worker;
  private ResponseStream response;

  private EventSourceClient(Builder builder) {
    this.url = Objects.requireNonNull(builder.url, "url");
    this.method = builder.method;
    this.body = builder.body;
    this.lastEventId = builder.lastEventId;
    this.connectTimeout = builder.connectTimeout;
    this.readTimeout = builder.readTimeout;
    this.followRedirects = builder.followRedirects;
    this.maxLineChars = builder.maxLineChars;
    this.maxEventChars = builder.maxEventChars;
    this.logger = builder.logger;

    Map<String, String> merged = new LinkedHashMap<>();
    merged.put("Accept", "text/event-stream");
    merged.putAll(builder.headers);
    this.headers = Map.copyOf(merged);

    this.ownsOpener = builder.opener == null;
    this.opener = ownsOpener ? new OkHttpStreamOpener() : builder.opener;

    this.onConnect = builder.onConnect;
    this.onDisconnect = builder.onDisconnect;
    this.onMessage = builder.onMessage;
    this.onComment = builder.onComment;
    this.onError = builder.onError;
    this.shouldReconnect = builder.shouldReconnect;
    this.calculateReconnectDelay = builder.calculateReconnectDelay;
  }

  public static Builder builder(String url) {
    return new Builder(url);
  }

  public ReadyState readyState() {
    return readyState;
  }

  public void connect() {
    synchronized (lock) {
      if (readyState != ReadyState.CLOSED) {
        return;
      }
      readyState = ReadyState.CONNECTING;

      // A fresh signal per reader: the one the last reader was closed out of stays set forever.
      StopSignal signal = new StopSignal();
      stop = signal;
      worker = new Thread(() -> run(signal), "configdirector-eventsource");
      worker.setDaemon(true);
      worker.start();
    }
  }

  // Identity is the question being asked: whether this is the very same thread, not an equal one.
  @SuppressWarnings("ReferenceEquality")
  @Override
  public void close() {
    StopSignal signal;
    Thread reader;
    ResponseStream open;
    synchronized (lock) {
      signal = stop;
      // Set under the same lock that writes the state below. Setting it afterwards leaves a
      // window where a reader has already passed its own isSet() check and publishes CONNECTING
      // last, stranding the client in a state every later connect() refuses.
      signal.set();
      readyState = ReadyState.CLOSED;
      reader = worker;
      open = response;
      worker = null;
    }

    // Drops the connection under the reader so a read blocked waiting for the next event gives up,
    // rather than holding close() until the server happens to send something.
    if (open != null) {
      cancelQuietly(open);
    }
    // Joining from the reader itself would deadlock, and close() is reachable from a handler.
    if (reader != null && reader != Thread.currentThread()) {
      try {
        reader.join(CLOSE_TIMEOUT.toMillis());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    // Last, so the reader is off the connection before its pool is released.
    if (ownsOpener) {
      closeQuietly(opener);
    }
  }

  private void run(StopSignal signal) {
    // Consecutive failures, owned by this loop alone. A connection that opened resets the count,
    // so a stream that comes back and drops again starts over at one.
    int attempt = 0;
    try {
      while (true) {
        Failure failure = connectOnce(signal);
        if (failure == null || signal.isSet()) {
          return;
        }

        attempt = failure.streamOpened() ? 1 : attempt + 1;
        ReconnectionState state =
            new ReconnectionState(attempt, serverDelay, failure.status(), failure.error());
        if (!evaluateShouldReconnect(state)) {
          disconnected(signal);
          return;
        }

        setState(ReadyState.CONNECTING, signal);
        if (signal.awaitFor(reconnectDelay(state))) {
          return;
        }
      }
    } catch (Exception error) {
      // A state left at OPEN would have callers believing there is still a reader on the stream,
      // and the reader must not die without saying why. An Error propagates as itself.
      setState(ReadyState.CLOSED, signal);
      logger.error("[EventSource] The connection loop stopped unexpectedly", error);
    }
  }

  // Null means the loop should stop; a Failure means try again.
  // Identity again: only this attempt's own stream may be cleared from the shared field.
  @SuppressWarnings("ReferenceEquality")
  private Failure connectOnce(StopSignal signal) {
    ResponseStream stream;
    try {
      stream = open(signal);
    } catch (Exception error) {
      if (signal.isSet()) {
        return null;
      }
      notifyError(error);
      return new Failure(null, error, false);
    }

    int status = stream.status();
    boolean opened = false;
    try {
      if (status == HTTP_NO_CONTENT) {
        disconnected(signal);
        return null;
      }
      if (status >= HTTP_BAD_REQUEST) {
        return new Failure(status, null, false);
      }

      // Published before the first read, so close() has something to interrupt.
      synchronized (lock) {
        response = stream;
      }
      if (signal.isSet()) {
        // close() landed in the gap and saw no response to cancel. Reading now would block on
        // something nothing is going to interrupt.
        return null;
      }

      beginStream(signal);
      opened = true;
      read(stream, signal);
    } catch (Exception error) {
      if (signal.isSet()) {
        return null;
      }
      notifyError(error);
      return new Failure(status, error, opened);
    } finally {
      synchronized (lock) {
        if (response == stream) {
          response = null;
        }
      }
      closeQuietly(stream);
    }

    if (signal.isSet()) {
      return null;
    }
    return new Failure(status, new StreamClosedException("The event stream was closed"), opened);
  }

  private ResponseStream open(StopSignal signal) {
    Map<String, String> requestHeaders = new LinkedHashMap<>(headers);
    String id = lastEventId;
    if (id != null) {
      requestHeaders.put("Last-Event-ID", id);
    }

    ResponseStream stream =
        opener.open(
            new StreamRequest(
                url, method, requestHeaders, body, connectTimeout, readTimeout, followRedirects));

    if (signal.isSet()) {
      closeQuietly(stream);
      throw new StreamClosedException("The client was closed while connecting");
    }
    return stream;
  }

  private void beginStream(StopSignal signal) {
    setState(ReadyState.OPEN, signal);
    notifyHandler("onConnect", onConnect);
  }

  private void read(ResponseStream stream, StopSignal signal) {
    EventSourceParser parser =
        EventSourceParser.builder()
            .onEvent(this::handleEvent)
            .onComment(comment -> notifyHandler("onComment", () -> onComment.accept(comment)))
            .onRetry(this::handleRetry)
            .maxLineChars(maxLineChars)
            .maxEventChars(maxEventChars)
            .build();

    char[] buffer = new char[READ_SIZE];
    while (true) {
      int count = stream.read(buffer);
      if (count < 0) {
        break;
      }
      if (signal.isSet()) {
        // Whatever arrived alongside close() is not delivered.
        return;
      }
      parser.feed(buffer, 0, count);
    }

    if (signal.isSet()) {
      return;
    }
    parser.finish();
  }

  private void handleEvent(EventSourceMessage message) {
    if (message.id() != null) {
      lastEventId = message.id();
    }
    notifyHandler("onMessage", () -> onMessage.accept(message));
  }

  private void handleRetry(int milliseconds) {
    serverDelay = Duration.ofMillis(milliseconds);
  }

  @SuppressWarnings("ReferenceEquality")
  private void disconnected(StopSignal signal) {
    synchronized (lock) {
      signal.set();
      if (stop == signal) {
        readyState = ReadyState.CLOSED;
      }
    }
    // Outside the lock: a handler is free to call close(), which takes it.
    notifyHandler("onDisconnect", onDisconnect);
  }

  // Only the reader that is still the current one may publish a state, and never once it has
  // been stopped: either would resurrect a state the client was closed out of.
  @SuppressWarnings("ReferenceEquality")
  private void setState(ReadyState state, StopSignal signal) {
    synchronized (lock) {
      if (!signal.isSet() && stop == signal) {
        readyState = state;
      }
    }
  }

  private Duration reconnectDelay(ReconnectionState state) {
    Duration fallback = serverDelay;
    Duration delay = evaluateReconnectDelay(state, fallback);
    if (delay == null
        || delay.compareTo(MIN_RECONNECT_DELAY) < 0
        || delay.compareTo(MAX_RECONNECT_DELAY) > 0) {
      notifyError(
          new ValueOutOfRangeException(
              "The calculated reconnect delay "
                  + delay
                  + " is out of range; falling back to "
                  + fallback));
      return fallback;
    }
    return delay;
  }

  private void notifyHandler(String name, Runnable action) {
    try {
      action.run();
    } catch (Exception error) {
      // A caller's handler must not take the connection down with it.
      logger.error("[EventSource] The {} handler threw", name, error);
    }
  }

  private void notifyError(Throwable error) {
    notifyHandler("onError", () -> onError.accept(error));
  }

  private boolean evaluateShouldReconnect(ReconnectionState state) {
    try {
      return shouldReconnect.test(state);
    } catch (Exception error) {
      logger.error("[EventSource] The shouldReconnect handler threw; reconnecting anyway", error);
      return true;
    }
  }

  private Duration evaluateReconnectDelay(ReconnectionState state, Duration fallback) {
    try {
      return calculateReconnectDelay.apply(state);
    } catch (Exception error) {
      logger.error(
          "[EventSource] The calculateReconnectDelay handler threw; using {}", fallback, error);
      return fallback;
    }
  }

  private void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception error) {
      logger.debug("[EventSource] Releasing a finished resource failed", error);
    }
  }

  private void cancelQuietly(ResponseStream stream) {
    try {
      stream.cancel();
    } catch (Exception error) {
      logger.debug("[EventSource] Cancelling the open stream failed", error);
    }
  }

  private record Failure(Integer status, Throwable error, boolean streamOpened) {}

  // Says whether a given reader should still be running, and doubles as its sleep.
  private static final class StopSignal {

    private final CountDownLatch latch = new CountDownLatch(1);

    static StopSignal alreadyStopped() {
      StopSignal signal = new StopSignal();
      signal.set();
      return signal;
    }

    boolean isSet() {
      return latch.getCount() == 0;
    }

    void set() {
      latch.countDown();
    }

    // True if the signal was set before the timeout elapsed.
    boolean awaitFor(Duration timeout) {
      try {
        return latch.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return true;
      }
    }
  }

  public static final class Builder {

    private final String url;
    private String method = "GET";
    private Map<String, String> headers = Map.of();
    private byte[] body;
    private String lastEventId;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ZERO;
    private boolean followRedirects = true;
    private StreamOpener opener;
    private Logger logger = LoggerFactory.getLogger(EventSourceClient.class);
    private int maxLineChars = EventSourceParser.DEFAULT_MAX_LINE_CHARS;
    private int maxEventChars = EventSourceParser.DEFAULT_MAX_EVENT_CHARS;

    private Runnable onConnect = () -> {};
    private Runnable onDisconnect = () -> {};
    private Consumer<EventSourceMessage> onMessage = message -> {};
    private Consumer<String> onComment = comment -> {};
    private Consumer<Throwable> onError = error -> {};
    private Predicate<ReconnectionState> shouldReconnect = state -> true;
    private Function<ReconnectionState, Duration> calculateReconnectDelay =
        ReconnectionState::serverReconnectDelay;

    private Builder(String url) {
      this.url = url;
    }

    public Builder method(String method) {
      this.method = Objects.requireNonNull(method, "method");
      return this;
    }

    // Merged over Accept: text/event-stream, which a caller may therefore override.
    public Builder headers(Map<String, String> headers) {
      this.headers = Map.copyOf(headers);
      return this;
    }

    public Builder body(byte[] body) {
      this.body = body;
      return this;
    }

    public Builder lastEventId(String lastEventId) {
      this.lastEventId = lastEventId;
      return this;
    }

    // Duration.ZERO means no limit.
    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
      return this;
    }

    // How long an open stream may stay silent before it is treated as dead. Duration.ZERO,
    // the default, waits indefinitely, which is what a stream fed by server keepalives wants.
    public Builder readTimeout(Duration readTimeout) {
      this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
      return this;
    }

    public Builder followRedirects(boolean followRedirects) {
      this.followRedirects = followRedirects;
      return this;
    }

    // An opener passed here stays the caller's to close.
    public Builder opener(StreamOpener opener) {
      this.opener = opener;
      return this;
    }

    public Builder logger(Logger logger) {
      this.logger = Objects.requireNonNull(logger, "logger");
      return this;
    }

    public Builder maxLineChars(int limit) {
      this.maxLineChars = limit;
      return this;
    }

    public Builder maxEventChars(int limit) {
      this.maxEventChars = limit;
      return this;
    }

    public Builder onConnect(Runnable handler) {
      this.onConnect = Objects.requireNonNull(handler, "onConnect");
      return this;
    }

    public Builder onDisconnect(Runnable handler) {
      this.onDisconnect = Objects.requireNonNull(handler, "onDisconnect");
      return this;
    }

    public Builder onMessage(Consumer<EventSourceMessage> handler) {
      this.onMessage = Objects.requireNonNull(handler, "onMessage");
      return this;
    }

    public Builder onComment(Consumer<String> handler) {
      this.onComment = Objects.requireNonNull(handler, "onComment");
      return this;
    }

    // An HTTP error status is not an error here; it reaches shouldReconnect as a status.
    public Builder onError(Consumer<Throwable> handler) {
      this.onError = Objects.requireNonNull(handler, "onError");
      return this;
    }

    // Defaults to always. A handler that throws is treated as having said yes.
    public Builder shouldReconnect(Predicate<ReconnectionState> handler) {
      this.shouldReconnect = Objects.requireNonNull(handler, "shouldReconnect");
      return this;
    }

    // Defaults to the delay the server asked for. A delay outside one millisecond to one hour is
    // reported through onError and replaced by the server's.
    public Builder calculateReconnectDelay(Function<ReconnectionState, Duration> handler) {
      this.calculateReconnectDelay = Objects.requireNonNull(handler, "calculateReconnectDelay");
      return this;
    }

    public EventSourceClient build() {
      return new EventSourceClient(this);
    }
  }
}
