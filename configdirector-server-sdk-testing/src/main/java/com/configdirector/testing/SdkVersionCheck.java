package com.configdirector.testing;

final class SdkVersionCheck {

  private static final String DEVELOPMENT_VERSION = "0.0.0-dev";

  private SdkVersionCheck() {}

  static void verify(String sdkVersion, String testingVersion) {
    if (sdkVersion == null
        || testingVersion == null
        || DEVELOPMENT_VERSION.equals(sdkVersion)
        || DEVELOPMENT_VERSION.equals(testingVersion)
        || sdkVersion.equals(testingVersion)) {
      return;
    }
    throw new IllegalStateException(
        "configdirector-server-sdk-testing "
            + testingVersion
            + " requires configdirector-server-sdk "
            + testingVersion
            + ", but "
            + sdkVersion
            + " is on the classpath. The testing artifact relies on the SDK's internals, so both"
            + " must be the same version.");
  }
}
