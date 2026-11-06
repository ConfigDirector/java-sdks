package com.configdirector.internal.telemetry;

import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigType;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

// Collects what the client evaluated and reports it on an interval.
public final class ScheduledTelemetryCollector implements TelemetryCollector {

  // The first flush comes early, so that a process that runs briefly still reports what it
  // evaluated.
  public static final Duration INITIAL_FLUSH_DELAY = Duration.ofSeconds(5);

  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

  // The queue limit is split between the two things a report carries. Evaluations outnumber the
  // distinct contexts they were evaluated against by a wide margin, so they get the larger share.
  private static final double EVALUATION_SHARE = 0.7;

  private final Logger logger;
  private final EventReporter reporter;
  private final EventQueue events;
  private final ContextRegistry contexts;
  private final InvalidContextSubmissions invalidContexts = new InvalidContextSubmissions();
  private final InvalidContextSubmissions invalidContextsAfterStopping =
      new InvalidContextSubmissions();
  private final TrackedEventQueue trackedEvents = new TrackedEventQueue();
  private final Duration flushInterval;
  private final ScheduledExecutorService scheduler;

  // Held for the whole of a flush, so a report triggered by close() cannot overtake one the
  // interval already started.
  private final Object flushLock = new Object();
  private final AtomicBoolean collecting = new AtomicBoolean(true);
  private final AtomicBoolean closed = new AtomicBoolean();

  private volatile ScheduledFuture<?> pendingFlush;

  public ScheduledTelemetryCollector(TelemetryCollectorOptions options) {
    this(
        options,
        new HttpEventReporter(
            options.serverSdkKey(),
            options.baseUrl(),
            options.identity(),
            options.metaContext(),
            options.logger(),
            options.http()));
  }

  ScheduledTelemetryCollector(TelemetryCollectorOptions options, EventReporter reporter) {
    this.logger = options.logger();
    this.reporter = reporter;
    this.flushInterval = options.flushInterval();

    int evaluationLimit = (int) Math.round(options.eventQueueLimit() * EVALUATION_SHARE);
    this.events = new EventQueue(evaluationLimit);
    this.contexts = new ContextRegistry(options.eventQueueLimit() - evaluationLimit);

    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "configdirector-telemetry");
              thread.setDaemon(true);
              return thread;
            });
    schedule(options.initialFlushDelay());
  }

  // On the client's hot path, so it returns without doing any appreciable work: rendering is
  // cheap, and the hashing an oversized value needs waits for the flush thread.
  @Override
  public void recordEvaluation(
      ConfigEvaluation evaluation,
      Object defaultValue,
      ConfigType type,
      String ruleId,
      String shareId) {
    if (!collecting.get()) {
      return;
    }

    ReportedContext reportedContext = ReportedContext.of(evaluation.context());
    if (reportedContext.identifier() != null) {
      contexts.add(reportedContext.kind(), reportedContext.identifier(), evaluation.context());
    }

    events.push(
        EvaluatedConfigEvent.of(
            evaluation,
            defaultValue,
            type,
            reportedContext.identifier(),
            reportedContext.kind(),
            ruleId,
            shareId));
  }

  @Override
  public InvalidContextRecording recordInvalidContext(String kindSample) {
    return (collecting.get() ? invalidContexts : invalidContextsAfterStopping).record(kindSample);
  }

  @Override
  public void recordTrackedEvent(TrackedEventAdmission admission) {
    if (!collecting.get()) {
      return;
    }

    for (TrackedEventWarning warning : trackedEvents.add(admission)) {
      logger.warn(
          "{} Further tracked events like it are not logged until the next telemetry batch is sent.",
          warning.message());
    }
  }

  // Reports everything collected so far without waiting for the next interval, blocking until the
  // request completes. Nothing is sent when there is nothing to report.
  public void flush() {
    ReporterResponse response;
    synchronized (flushLock) {
      EventQueue.Snapshot evaluations = events.takeSnapshot();
      ContextRegistry.Snapshot captured = contexts.takeSnapshot();
      EventReport report =
          new EventReport(
              AggregatedEvent.aggregate(compacted(evaluations)),
              evaluations.droppedCount(),
              captured.contexts(),
              captured.droppedCount(),
              invalidContexts.takeSnapshot(),
              trackedEvents.takeBatch());
      if (report.isEmpty()) {
        return;
      }

      try {
        response = reporter.report(report);
      } catch (RuntimeException error) {
        // A failed report must not take the flush thread down with it.
        logger.warn("[TelemetryCollector] Error reporting telemetry data", error);
        return;
      }
    }

    if (response.fatal()) {
      stopCollecting();
    }
  }

  // Reports whatever is left and stops collecting. Safe to call more than once.
  @Override
  public void close() {
    close(SHUTDOWN_TIMEOUT);
  }

  // The same, on a budget: waiting for a report already in flight and sending the last one both
  // come out of `timeout`, rather than each having a timeout of its own for the caller to pay.
  // The same, waiting no longer than `timeout` for the last report. That report is best effort:
  // a caller with no time to spare abandons it, on a daemon thread that will not hold the
  // process open.
  @Override
  public void close(Duration timeout) {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    boolean wasCollecting = collecting.getAndSet(false);

    ScheduledFuture<?> pending = pendingFlush;
    if (pending != null) {
      // Not an interrupt: a report already in flight is left to finish.
      pending.cancel(false);
    }
    // On the scheduler rather than inline, so the await below is the only wait close() does.
    if (wasCollecting) {
      submitFinalReport();
    }
    scheduler.shutdown();
    try {
      scheduler.awaitTermination(Math.max(timeout.toMillis(), 0L), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    clearWhatWasCollected();
  }

  private void clearWhatWasCollected() {
    events.clear();
    contexts.clear();
    invalidContexts.clear();
    trackedEvents.clear();
  }

  private void submitFinalReport() {
    try {
      scheduler.execute(this::flush);
    } catch (RejectedExecutionException alreadyStopping) {
      logger.debug("[TelemetryCollector] The scheduler was already stopping; no final report");
    }
  }

  private static EventQueue.Snapshot compacted(EventQueue.Snapshot snapshot) {
    return new EventQueue.Snapshot(
        snapshot.startTime(),
        snapshot.endTime(),
        snapshot.events().stream().map(EvaluatedConfigEvent::compacted).toList(),
        snapshot.droppedCount());
  }

  private void schedule(Duration delay) {
    try {
      pendingFlush =
          scheduler.schedule(this::flushAndReschedule, delay.toMillis(), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException closing) {
      // close() got there first; whatever is left is reported by close() itself.
    }
  }

  private void flushAndReschedule() {
    try {
      flush();
    } catch (RuntimeException error) {
      // A scheduled task that throws is never run again, which would end telemetry silently.
      logger.warn("[TelemetryCollector] The telemetry flush failed unexpectedly", error);
    }
    if (collecting.get()) {
      schedule(flushInterval);
    }
  }

  private void stopCollecting() {
    if (!collecting.compareAndSet(true, false)) {
      return;
    }
    clearWhatWasCollected();
    logger.warn(
        "[TelemetryCollector] Received a fatal error while reporting telemetry. No longer "
            + "collecting events.");
  }
}
