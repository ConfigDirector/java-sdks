package com.configdirector.internal.transport;

// How the SDK retrieves config state from ConfigDirector.
public enum ConnectionMode {
  STREAMING,
  POLLING,
  ONE_TIME
}
