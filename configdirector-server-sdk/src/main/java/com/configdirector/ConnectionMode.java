package com.configdirector;

/** How the SDK retrieves config state from ConfigDirector. */
public enum ConnectionMode {
  /** The connection stays open and receives updates as config state changes. */
  STREAMING,

  /**
   * Config state is fetched during initialization, then re-fetched every polling interval. The
   * interval defaults to 5 minutes and the minimum is 60 seconds; a value below the minimum is
   * raised to the minimum and a warning is logged.
   */
  POLLING,

  /**
   * The client never connects and sends nothing. It serves the values given with {@link
   * ConnectionOptions.Builder#file} or {@link ConnectionOptions.Builder#values}, the same value to
   * every context, or every in-code default value when neither is given. The server SDK key is not
   * needed, and the polling interval, timeout and URL are ignored.
   */
  OFFLINE
}
