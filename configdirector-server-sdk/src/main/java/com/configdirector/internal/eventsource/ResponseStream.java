package com.configdirector.internal.eventsource;

/**
 * Implementations must translate their transport's failures into this package's exception types, so
 * that swapping the {@link StreamOpener} does not change what {@link EventSourceClient} handles.
 */
public interface ResponseStream extends AutoCloseable {

  int status();

  /**
   * Blocks until at least one character arrives and returns whatever is available rather than
   * filling {@code destination}, so a slow trickle of events is still delivered promptly. Returns
   * -1 at end of stream. The body is decoded as UTF-8, which SSE mandates.
   */
  int read(char[] destination);

  /**
   * Ends a parked {@link #read} from another thread. Must not wait for that reader and must not
   * release the response: tearing it down here would park the caller behind the read it is trying
   * to end, so the reader does that itself on its way out.
   */
  void cancel();

  /** Only the thread that drove {@link #read} may call this. */
  @Override
  void close();
}
