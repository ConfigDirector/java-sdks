package com.configdirector.internal.client;

import com.configdirector.internal.transport.ConfigBundle;
import com.configdirector.internal.transport.Transport;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

final class OfflineTransport implements Transport {

  private static final String ENVIRONMENT_ID = "offline";
  private static final String PROJECT_ID = "offline";

  private final OfflineSource source;
  private final Consumer<ConfigBundle> onBundle;
  private volatile boolean connected;

  OfflineTransport(OfflineSource source, Consumer<ConfigBundle> onBundle) {
    this.source = source;
    this.onBundle = onBundle;
  }

  @Override
  public void connect(Duration timeout) {
    ConfigBundle bundle =
        new ConfigBundle(
            source.loadConfigs(),
            Map.of(),
            ConfigBundle.BundleKind.FULL,
            ENVIRONMENT_ID,
            PROJECT_ID,
            null);
    connected = true;
    onBundle.accept(bundle);
  }

  @Override
  public boolean isConnected() {
    return connected;
  }

  @Override
  public void close(Duration timeout) {
    connected = false;
  }
}
