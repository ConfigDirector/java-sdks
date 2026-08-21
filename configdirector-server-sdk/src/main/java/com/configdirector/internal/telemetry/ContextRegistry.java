package com.configdirector.internal.telemetry;

import com.configdirector.Context;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The distinct contexts seen since the last flush, reported so the dashboard can show who a config
// was evaluated for.
public final class ContextRegistry {

  private final int limit;
  private final Map<String, Context> contexts = new LinkedHashMap<>();
  private final Object lock = new Object();

  private int droppedCount;

  public ContextRegistry(int limit) {
    this.limit = Math.max(1, limit);
  }

  public void add(String contextId, Context context) {
    synchronized (lock) {
      // Re-inserting an existing key leaves it where it was, so a context that keeps being seen is
      // no safer from eviction than one seen once. The other SDKs' Maps behave the same way.
      contexts.put(contextId, context);
      Iterator<String> oldest = contexts.keySet().iterator();
      while (contexts.size() > limit && oldest.hasNext()) {
        oldest.next();
        oldest.remove();
        droppedCount++;
      }
    }
  }

  public Snapshot takeSnapshot() {
    synchronized (lock) {
      Snapshot snapshot = new Snapshot(List.copyOf(contexts.values()), droppedCount);
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
    contexts.clear();
    droppedCount = 0;
  }

  public record Snapshot(List<Context> contexts, int droppedCount) {}
}
