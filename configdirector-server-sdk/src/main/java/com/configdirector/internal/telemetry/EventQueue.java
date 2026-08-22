package com.configdirector.internal.telemetry;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// Holds what has been evaluated since the last flush. Bounded: an application evaluating faster
// than telemetry can report must lose the oldest events rather than the host's memory.
//
// push() runs on the caller's thread for every config read, so it never waits for the lock. An
// application evaluating fast enough to contend here is already evaluating far faster than the
// queue can hold between flushes, and losing one more event to a busy lock costs less than parking
// the thread, which on a framework that evaluates configs from an event loop would stall every
// request that loop is serving. A skipped event is counted as dropped, the same as one evicted for
// space, so a report still says how much it is missing.
public final class EventQueue {

  private final int limit;
  private final Deque<EvaluatedConfigEvent> events = new ArrayDeque<>();
  private final Lock lock = new ReentrantLock();

  private final AtomicInteger droppedCount = new AtomicInteger();

  private Instant startTime;

  public EventQueue(int limit) {
    this.limit = Math.max(1, limit);
  }

  public void push(EvaluatedConfigEvent event) {
    if (!lock.tryLock()) {
      droppedCount.incrementAndGet();
      return;
    }
    try {
      if (startTime == null) {
        startTime = Instant.now();
      }
      if (events.size() >= limit) {
        events.removeFirst();
        droppedCount.incrementAndGet();
      }
      events.addLast(event);
    } finally {
      lock.unlock();
    }
  }

  // Empties the queue, leaving it ready to collect the next batch. The dropped count is taken
  // rather than zeroed, so a push that loses the race to this very call is still counted once --
  // in this batch or the next.
  public Snapshot takeSnapshot() {
    lock.lock();
    try {
      Instant endTime = Instant.now();
      Snapshot snapshot =
          new Snapshot(
              startTime == null ? endTime : startTime,
              endTime,
              List.copyOf(events),
              droppedCount.getAndSet(0));
      events.clear();
      startTime = null;
      return snapshot;
    } finally {
      lock.unlock();
    }
  }

  public void clear() {
    lock.lock();
    try {
      events.clear();
      startTime = null;
      droppedCount.set(0);
    } finally {
      lock.unlock();
    }
  }

  public record Snapshot(
      Instant startTime, Instant endTime, List<EvaluatedConfigEvent> events, int droppedCount) {}
}
