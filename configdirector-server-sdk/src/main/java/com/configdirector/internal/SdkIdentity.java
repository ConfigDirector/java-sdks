package com.configdirector.internal;

import java.util.Objects;

// Identifies the SDK, or the wrapper built on it, to the server. The version is read from the
// manifest of the jar holding the anchor class so it cannot drift from what was actually
// published; a development build has no manifest and says so.
public record SdkIdentity(String name, String version) {

  private static final String DEVELOPMENT_VERSION = "0.0.0-dev";

  public static final SdkIdentity SERVER_SDK = of("java-server-sdk", SdkIdentity.class);

  public SdkIdentity {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
  }

  public static SdkIdentity of(String name, Class<?> anchor) {
    Package anchorPackage = anchor.getPackage();
    String version = anchorPackage == null ? null : anchorPackage.getImplementationVersion();
    return new SdkIdentity(name, version == null ? DEVELOPMENT_VERSION : version);
  }

  public String userAgent() {
    return name + "/" + version;
  }
}
