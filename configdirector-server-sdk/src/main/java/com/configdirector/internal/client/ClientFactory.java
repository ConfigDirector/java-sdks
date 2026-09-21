package com.configdirector.internal.client;

import com.configdirector.ClientOptions;
import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.internal.SdkIdentity;
import java.lang.invoke.MethodHandles;
import java.util.Objects;
import java.util.function.Consumer;

public final class ClientFactory {

  @FunctionalInterface
  public interface Builder {
    ConfigDirectorClient build(
        String serverSdkKey, Consumer<ClientOptions> configure, SdkIdentity identity);
  }

  private static volatile Builder builder;

  private ClientFactory() {}

  public static void register(Builder registered) {
    builder = Objects.requireNonNull(registered, "registered");
  }

  public static ConfigDirectorClient create(
      String serverSdkKey, Consumer<ClientOptions> configure, SdkIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    try {
      MethodHandles.lookup().ensureInitialized(ConfigDirector.class);
    } catch (IllegalAccessException inaccessible) {
      throw new IllegalStateException(inaccessible);
    }
    return builder.build(serverSdkKey, configure, identity);
  }
}
