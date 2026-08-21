package com.configdirector.internal.eventsource;

// Implementations must translate their transport's failures into this package's exception types,
// so that swapping the StreamOpener does not change what EventSourceClient has to handle.
public interface ResponseStream extends AutoCloseable {

  int status();

  // Blocks until at least one character arrives and returns whatever is available rather than
  // filling destination, so a slow trickle of events is still delivered promptly. -1 ends the
  // stream. The body is decoded as UTF-8, which SSE mandates.
  int read(char[] destination);

  // Ends a parked read from another thread. Must not wait for that reader and must not release the
  // response: tearing it down here would park the caller behind the read it is trying to end, so
  // the reader does that itself on its way out.
  void cancel();

  // Only the thread that drove read() may call this.
  @Override
  void close();
}
