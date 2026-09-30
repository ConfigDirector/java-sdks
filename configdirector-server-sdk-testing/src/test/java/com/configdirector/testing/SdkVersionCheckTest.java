package com.configdirector.testing;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;

class SdkVersionCheckTest {

  @Test
  void the_same_version_on_both_sides_passes() {
    assertThatNoException().isThrownBy(() -> SdkVersionCheck.verify("1.7.0", "1.7.0"));
  }

  @Test
  void an_unknown_version_on_either_side_is_not_checked() {
    assertThatNoException().isThrownBy(() -> SdkVersionCheck.verify(null, "1.7.0"));
    assertThatNoException().isThrownBy(() -> SdkVersionCheck.verify("1.7.0", null));
  }

  @Test
  void a_development_build_on_either_side_is_not_checked() {
    assertThatNoException().isThrownBy(() -> SdkVersionCheck.verify("0.0.0-dev", "1.7.0"));
    assertThatNoException().isThrownBy(() -> SdkVersionCheck.verify("1.7.0", "0.0.0-dev"));
  }

  @Test
  void different_versions_are_refused_naming_both() {
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> SdkVersionCheck.verify("1.7.1", "1.7.0"))
        .withMessageContaining("configdirector-server-sdk-testing 1.7.0")
        .withMessageContaining("requires configdirector-server-sdk 1.7.0")
        .withMessageContaining("1.7.1 is on the classpath");
  }
}
