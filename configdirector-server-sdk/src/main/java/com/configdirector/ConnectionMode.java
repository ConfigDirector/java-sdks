package com.configdirector;

/** How the SDK retrieves config state from ConfigDirector. */
public enum ConnectionMode {
  /** The connection stays open and receives updates as config state changes. */
  STREAMING,

  /** Config state is fetched during initialization, then re-fetched every polling interval. */
  POLLING,

  /** Config state is fetched during initialization only, and never refreshed. */
  ONE_TIME
}
