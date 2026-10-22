package com.configdirector.internal.transport;

@FunctionalInterface
public interface TransportFactory {

  Transport create(LiveConnectionMode mode, TransportOptions options);
}
