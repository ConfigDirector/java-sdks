package com.configdirector.internal.eventsource;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Reads block where a socket would, so cancel() can unblock a reader parked mid-stream. */
final class FakeResponseStream implements ResponseStream {

  private enum Control {
    END,
    CANCELLED
  }

  private final int status;
  private final BlockingQueue<Object> pending = new LinkedBlockingQueue<>();
  private final AtomicInteger closeCount = new AtomicInteger();
  private final AtomicInteger cancelCount = new AtomicInteger();
  private final CountDownLatch parked = new CountDownLatch(1);

  private char[] leftover;
  private int leftoverOffset;

  private FakeResponseStream(int status) {
    this.status = status;
  }

  static FakeResponseStream of(int status, String body) {
    FakeResponseStream stream = new FakeResponseStream(status);
    if (!body.isEmpty()) {
      stream.push(body);
    }
    stream.endOfStream();
    return stream;
  }

  /** Reads block until the test pushes more or ends the stream. */
  static FakeResponseStream live(int status) {
    return new FakeResponseStream(status);
  }

  void push(String text) {
    pending.add(text.toCharArray());
  }

  void endOfStream() {
    pending.add(Control.END);
  }

  void awaitParkedRead() throws InterruptedException {
    if (!parked.await(5, TimeUnit.SECONDS)) {
      throw new AssertionError("No reader ever parked on this stream");
    }
  }

  int closeCount() {
    return closeCount.get();
  }

  int cancelCount() {
    return cancelCount.get();
  }

  @Override
  public int status() {
    return status;
  }

  @Override
  public int read(char[] destination) {
    char[] chunk = leftover;
    int offset = leftoverOffset;

    if (chunk == null) {
      parked.countDown();
      Object next = take();
      if (next == Control.CANCELLED) {
        throw new StreamClosedException("The stream was cancelled");
      }
      if (next == Control.END) {
        pending.add(Control.END);
        return -1;
      }
      chunk = (char[]) next;
      offset = 0;
    }

    int count = Math.min(destination.length, chunk.length - offset);
    System.arraycopy(chunk, offset, destination, 0, count);

    if (offset + count < chunk.length) {
      leftover = chunk;
      leftoverOffset = offset + count;
    } else {
      leftover = null;
      leftoverOffset = 0;
    }
    return count;
  }

  @Override
  public void cancel() {
    cancelCount.incrementAndGet();
    pending.add(Control.CANCELLED);
  }

  @Override
  public void close() {
    closeCount.incrementAndGet();
  }

  private Object take() {
    try {
      Object next = pending.poll(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
      if (next == null) {
        throw new AssertionError("A read waited 10s for a body the test never wrote");
      }
      return next;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new StreamClosedException("The read was interrupted");
    }
  }
}
