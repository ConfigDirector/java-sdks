package com.configdirector.internal.client;

import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.ConnectionOptions;
import com.configdirector.internal.transport.LiveConnectionMode;

sealed interface ConnectionPlan {

  String DEFAULT_BASE_URL = "https://server-sdk-api.configdirector.com";

  record Connect(LiveConnectionMode mode, String serverSdkKey, String baseUrl)
      implements ConnectionPlan {}

  record StayOffline(OfflineSource source) implements ConnectionPlan {}

  static ConnectionPlan of(String serverSdkKey, ConnectionOptions connection) {
    return switch (connection.mode()) {
      case STREAMING -> liveModePlan(LiveConnectionMode.STREAMING, serverSdkKey, connection);
      case POLLING -> liveModePlan(LiveConnectionMode.POLLING, serverSdkKey, connection);
      case OFFLINE -> new StayOffline(offlineSourceOf(connection));
    };
  }

  private static ConnectionPlan liveModePlan(
      LiveConnectionMode mode, String serverSdkKey, ConnectionOptions connection) {
    if (serverSdkKey == null || serverSdkKey.isBlank()) {
      throw new ConfigDirectorValidationException(
          "No server SDK key was provided, the client cannot be instantiated without a valid "
              + "server SDK key");
    }
    if (connection.file() != null || connection.values() != null) {
      return new StayOffline(
          new OfflineSource.Rejected(
              "The connection options file and values apply only when the connection mode is"
                  + " OFFLINE, but the mode is "
                  + mode
                  + "."));
    }
    return new Connect(mode, serverSdkKey, baseUrlOf(connection.url()));
  }

  private static OfflineSource offlineSourceOf(ConnectionOptions connection) {
    if (connection.file() != null && connection.values() != null) {
      return new OfflineSource.Rejected(
          "An offline connection takes either a file or values, not both.");
    }
    if (connection.file() != null) {
      return new OfflineSource.ValuesFile(connection.file());
    }
    if (connection.values() != null) {
      return new OfflineSource.InlineValues(connection.values());
    }
    return new OfflineSource.InCodeDefaults();
  }

  private static String baseUrlOf(String url) {
    return url == null || url.isBlank() ? DEFAULT_BASE_URL : url.strip();
  }
}
