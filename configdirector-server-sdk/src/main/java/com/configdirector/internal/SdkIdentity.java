package com.configdirector.internal;

// Identifies this SDK to the server. The version is read from the jar's manifest so it cannot
// drift from what was actually published; a development build has no manifest and says so.
public final class SdkIdentity {

  public static final String NAME = "java-server-sdk";

  private static final String DEVELOPMENT_VERSION = "0.0.0-dev";

  private SdkIdentity() {}

  public static String version() {
    Package sdkPackage = SdkIdentity.class.getPackage();
    String version = sdkPackage == null ? null : sdkPackage.getImplementationVersion();
    return version == null ? DEVELOPMENT_VERSION : version;
  }

  public static String userAgent() {
    return NAME + "/" + version();
  }
}
