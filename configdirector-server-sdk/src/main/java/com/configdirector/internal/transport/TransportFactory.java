package com.configdirector.internal.transport;

@FunctionalInterface
public interface TransportFactory {

  Transport create(ConnectionMode mode, TransportOptions options);
}
