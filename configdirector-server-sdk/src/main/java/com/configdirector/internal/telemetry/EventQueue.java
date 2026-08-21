package com.configdirector.internal.telemetry;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

// Holds what has been evaluated since the last flush. Bounded: an application evaluating faster
// than telemetry can report must lose the oldest events rather than the host's memory.
public final class EventQueue {

  private final int limit;
  private final Deque<EvaluatedConfigEvent> events = new ArrayDeque<>();
  private final Object lock = new Object();

  private Instant startTime;
  private int droppedCount;

  public EventQueue(int limit) {
    this.limit = Math.max(1, limit);
  }

  public void push(EvaluatedConfigEvent event) {
    synchronized (lock) {
      if (startTime == null) {
        startTime = Instant.now();
      }
      if (events.size() >= limit) {
        events.removeFirst();
        droppedCount++;
      }
      events.addLast(event);
    }
  }

  // Empties the queue, leaving it ready to collect the next batch.
  public Snapshot takeSnapshot() {
    synchronized (lock) {
      Instant endTime = Instant.now();
      Snapshot snapshot =
          new Snapshot(
              startTime == null ? endTime : startTime,
              endTime,
              List.copyOf(events),
              droppedCount);
      reset();
      return snapshot;
    }
  }

  public void clear() {
    synchronized (lock) {
      reset();
    }
  }

  private void reset() {
    events.clear();
    startTime = null;
    droppedCount = 0;
  }

  public record Snapshot(
      Instant startTime, Instant endTime, List<EvaluatedConfigEvent> events, int droppedCount) {}
}
