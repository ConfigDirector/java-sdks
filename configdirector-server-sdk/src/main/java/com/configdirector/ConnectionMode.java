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
  POLLING
}
