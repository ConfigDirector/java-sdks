package com.configdirector.internal.eventsource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Each attempt takes the next scripted response, or the repeating one once the script runs out. */
final class FakeStreamOpener implements StreamOpener {

  private final List<StreamRequest> requests = Collections.synchronizedList(new ArrayList<>());
  private final Queue<Supplier<ResponseStream>> scripted = new ConcurrentLinkedQueue<>();
  private final AtomicInteger closeCount = new AtomicInteger();

  private volatile Supplier<ResponseStream> repeating = () -> FakeResponseStream.of(200, "");

  FakeStreamOpener script(Supplier<ResponseStream> response) {
    scripted.add(response);
    return this;
  }

  FakeStreamOpener thereafter(Supplier<ResponseStream> response) {
    this.repeating = response;
    return this;
  }

  static Supplier<ResponseStream> failing(RuntimeException error) {
    return () -> {
      throw error;
    };
  }

  int attemptCount() {
    return requests.size();
  }

  int closeCount() {
    return closeCount.get();
  }

  List<StreamRequest> requests() {
    synchronized (requests) {
      return List.copyOf(requests);
    }
  }

  StreamRequest lastRequest() {
    List<StreamRequest> seen = requests();
    if (seen.isEmpty()) {
      throw new AssertionError("No connection was ever attempted");
    }
    return seen.get(seen.size() - 1);
  }

  @Override
  public ResponseStream open(StreamRequest request) {
    requests.add(request);
    Supplier<ResponseStream> next = scripted.poll();
    return (next != null ? next : repeating).get();
  }

  @Override
  public void close() {
    closeCount.incrementAndGet();
  }
}
