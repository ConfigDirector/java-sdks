package com.configdirector.internal.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ValueIdsTest {

  private static final String BASE62 =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

  @Nested
  @DisplayName("generating a value ID")
  class Generating {

    @ParameterizedTest
    @CsvSource({
      "hello, 1MoOW7eqAPjhZeoELVwO9G",
      "world, 2Cg0gndCS8p6nDE5aa6LcI",
      "42, 3VWjGpOwynZPh07ivDC56c",
    })
    void matches_what_the_other_sdks_produce(String value, String expected) {
      // Taken from the JavaScript SDK's suite: every SDK has to agree on these, or the same config
      // value would be counted as two different ones in the dashboard.
      assertThat(ValueIds.generate(value)).isEqualTo(expected);
    }

    @Test
    void matches_the_other_sdks_for_the_empty_string() {
      assertThat(ValueIds.generate("")).isEqualTo("6ve2WrOl3mnciB6WIL2fIa");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "unicode ☂ café", "a much longer value repeated many times"})
    void is_always_the_same_length(String value) {
      assertThat(ValueIds.generate(value)).hasSize(ValueIds.VALUE_ID_LENGTH);
    }

    @Test
    void uses_only_base62_characters() {
      assertThat(ValueIds.generate("hello").chars())
          .allMatch(character -> BASE62.indexOf(character) >= 0);
    }

    @ParameterizedTest
    @CsvSource({
      "seek-438, 01HIHOQ1EOGUUUxjw3XzTY",
      "seek-465, 00LlHyAvF0ZgWilmdRpxJb",
    })
    void pads_a_digest_with_leading_zero_bytes(String value, String expected) {
      // These hash to a number small enough that its base62 form is shorter than the fixed width,
      // so the leading zeros have to be written rather than dropped.
      assertThat(ValueIds.generate(value)).isEqualTo(expected);
    }

    @Test
    void is_deterministic() {
      assertThat(ValueIds.generate("my-value")).isEqualTo(ValueIds.generate("my-value"));
    }

    @Test
    void different_values_produce_different_ids() {
      assertThat(ValueIds.generate("value-a")).isNotEqualTo(ValueIds.generate("value-b"));
    }

    @Test
    void hashes_the_utf8_encoding() {
      // A digest taken over some other encoding would not match the other SDKs.
      assertThat(ValueIds.generate("café")).isNotEqualTo(ValueIds.generate("cafe"));
    }
  }
}
