package com.configdirector.internal.telemetry;

import com.configdirector.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

// The distinct contexts seen since the last flush, reported so the dashboard can show who a config
// was evaluated for.
//
// Lock free for the same reason as EventQueue: add() runs on the caller's thread for every config
// read against an identified context. The queue alongside the map carries insertion order, which
// a ConcurrentHashMap does not keep and eviction needs.
public final class ContextRegistry {

  private final int limit;
  private final Map<Identity, Context> contexts = new ConcurrentHashMap<>();
  private final Queue<Identity> insertionOrder = new ConcurrentLinkedQueue<>();
  private final AtomicInteger droppedCount = new AtomicInteger();

  public ContextRegistry(int limit) {
    this.limit = Math.max(1, limit);
  }

  public void add(String contextKind, String contextId, Context context) {
    Identity identity = new Identity(contextKind, contextId);
    // A context already recorded is left alone. Worth the read: a server building an equal context
    // per request would otherwise write to the same entry on every evaluation, and threads writing
    // to one entry of a ConcurrentHashMap contend on it.
    if (context.equals(contexts.get(identity))) {
      return;
    }

    // Re-inserting an existing key leaves it where it was, so a context that keeps being seen is
    // no safer from eviction than one seen once. The other SDKs' Maps behave the same way.
    if (contexts.put(identity, context) != null) {
      return;
    }
    insertionOrder.add(identity);
    while (contexts.size() > limit) {
      Identity oldest = insertionOrder.poll();
      if (oldest == null) {
        return;
      }
      if (contexts.remove(oldest) != null) {
        droppedCount.incrementAndGet();
      }
    }
  }

  // Walked in insertion order rather than the map's, which a ConcurrentHashMap does not keep.
  public Snapshot takeSnapshot() {
    List<Context> captured = new ArrayList<>(contexts.size());
    for (Identity identity : insertionOrder) {
      Context context = contexts.get(identity);
      if (context != null) {
        captured.add(context);
      }
    }
    int dropped = droppedCount.get();
    clear();
    return new Snapshot(List.copyOf(captured), dropped);
  }

  public void clear() {
    contexts.clear();
    insertionOrder.clear();
    droppedCount.set(0);
  }

  public record Snapshot(List<Context> contexts, int droppedCount) {}

  private record Identity(String kind, String id) {}
}
