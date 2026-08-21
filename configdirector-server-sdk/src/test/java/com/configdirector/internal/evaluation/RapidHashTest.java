package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Vectors pinning the port against the reference implementation.
 * They cover each short-input length, the 16/17-byte boundary, the 112-byte
 * block loop and its remainder cases, and multi-byte UTF-8.
 */
class RapidHashTest {

  private static final long SEED = 0x397832987L;

  static Stream<Arguments> vectors() {
    return Stream.of(
        Arguments.of("", "5377612543505373799"),
        Arguments.of("a", "7674800498429868151"),
        Arguments.of("ab", "12048270741005468339"),
        Arguments.of("abc", "8205525400821834274"),
        Arguments.of("abcd", "2559843570930408943"),
        Arguments.of("abcde", "17182111687207956362"),
        Arguments.of("abcdefg", "13135472276134024436"),
        Arguments.of("abcdefgh", "10825195283420988801"),
        Arguments.of("abcdefghi", "9324307379318471710"),
        Arguments.of("0123456789abcdef", "8410398172536096822"),
        Arguments.of("0123456789abcdefg", "8975841632926530338"),
        Arguments.of("10-11111111-1111-4111-8111-111111111111", "7715065197445012089"),
        Arguments.of("x".repeat(48), "1185046273860983588"),
        Arguments.of("y".repeat(112), "5679430438346846087"),
        Arguments.of("z".repeat(113), "14103938338420400619"),
        Arguments.of("w".repeat(240), "15088383192705595115"),
        Arguments.of("héllo wörld ✓", "7481766294562949397"));
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("vectors")
  void matches_the_reference_implementation(String message, String expected) {
    long actual = RapidHash.hash(message.getBytes(StandardCharsets.UTF_8), SEED);

    assertThat(Long.toUnsignedString(actual)).isEqualTo(expected);
  }

  @Test
  void the_seed_changes_the_result() {
    byte[] message = "abc".getBytes(StandardCharsets.UTF_8);

    assertThat(RapidHash.hash(message, SEED)).isNotEqualTo(RapidHash.hash(message, SEED + 1));
    assertThat(RapidHash.hash(message, 0)).isNotEqualTo(RapidHash.hash(message, SEED));
  }

  // Pins the bucket assignment, not just the hash: the operands are joined as
  // "<identifier>-<configId>", and joining them the other way round would still hash cleanly while
  // silently putting every user in a different bucket from the other SDKs.
  @ParameterizedTest(name = "[{index}] {0}/{1}")
  @CsvSource({
    "config-1, user-1, 67.8",
    "config-1, user-2, 57.5",
    "abc, xyz, 35.2",
    "11111111-1111-4111-8111-111111111111, 10, 8.9",
    "c, u, 40.0",
  })
  void assigns_the_same_bucket_as_the_other_sdks(String configId, String identifier, double expected) {
    assertThat(PercentHashing.assignPercentage(configId, identifier)).isEqualTo(expected);
  }

  @Test
  void assigns_a_bucket_for_empty_operands() {
    assertThat(PercentHashing.assignPercentage("", "")).isEqualTo(20.9);
  }

  @Test
  void assigns_a_percentage_inside_the_bucket_range() {
    for (int i = 0; i < 1_000; i++) {
      double assigned = PercentHashing.assignPercentage("config-" + i, "user-" + i);

      assertThat(assigned).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(99.9);
    }
  }

  @Test
  void assigns_the_same_bucket_for_the_same_pair() {
    double first = PercentHashing.assignPercentage("config-a", "user-1");
    double again = PercentHashing.assignPercentage("config-a", "user-1");

    assertThat(first).isEqualTo(again);
  }

  @Test
  void spreads_identifiers_across_the_range() {
    List<Double> assigned =
        java.util.stream.IntStream.range(0, 2_000)
            .mapToObj(i -> PercentHashing.assignPercentage("config-a", "user-" + i))
            .toList();

    // A hash that collapsed to a constant would still pass the range check above.
    assertThat(assigned.stream().distinct().count()).isGreaterThan(500);
  }
}
