package com.configdirector.internal.eventsource;

public interface StreamOpener extends AutoCloseable {

  /**
   * Returns once the status is known, without consuming the body. An error status is still a
   * response: the client decides what to do with it. Only failing to get any response throws.
   */
  ResponseStream open(StreamRequest request);

  @Override
  default void close() {}
}
