package com.configdirector.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SdkIdentityTest {

  @Test
  void reads_the_version_from_the_manifest_of_the_jar_holding_the_anchor() {
    String published = Test.class.getPackage().getImplementationVersion();

    SdkIdentity identity = SdkIdentity.of("some-wrapper", Test.class);

    assertThat(published).isNotBlank();
    assertThat(identity.version()).isEqualTo(published);
  }

  @Test
  void says_so_when_the_anchor_comes_from_a_development_build() {
    assertThat(SdkIdentity.of("some-wrapper", SdkIdentityTest.class).version())
        .isEqualTo("0.0.0-dev");
  }

  @Test
  void names_the_server_sdk_by_default() {
    assertThat(SdkIdentity.SERVER_SDK.name()).isEqualTo("java-server-sdk");
  }

  @Test
  void joins_name_and_version_into_the_user_agent() {
    assertThat(new SdkIdentity("some-wrapper", "1.2.3").userAgent())
        .isEqualTo("some-wrapper/1.2.3");
  }
}
