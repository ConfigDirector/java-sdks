package com.configdirector;

/** Undoes a watch or an event registration. Closing twice is harmless. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

  @Override
  void close();
}
