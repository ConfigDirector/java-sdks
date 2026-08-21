package com.configdirector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetadataTest {

  @Test
  void carries_both_fields() {
    Metadata metadata = new Metadata("checkout", "1.2.3");

    assertThat(metadata.appName()).isEqualTo("checkout");
    assertThat(metadata.appVersion()).isEqualTo("1.2.3");
  }

  @Test
  void the_empty_metadata_carries_nothing() {
    assertThat(Metadata.empty().appName()).isNull();
    assertThat(Metadata.empty().appVersion()).isNull();
  }

  @Test
  void is_a_value_type() {
    assertThat(new Metadata("a", "1")).isEqualTo(new Metadata("a", "1"));
    assertThat(new Metadata("a", "1")).isNotEqualTo(new Metadata("a", "2"));
  }
}
