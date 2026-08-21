package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonPointerTest {

  private static final Map<String, Object> DOCUMENT = document();

  private static Map<String, Object> document() {
    Map<String, Object> nested = new HashMap<>();
    nested.put("b", 1);

    Map<String, Object> root = new HashMap<>();
    root.put("a", nested);
    root.put("arr", List.of(10, 20));
    root.put("nulls", Arrays.asList(1, null, 3));
    root.put("n", null);
    root.put("", "empty-key");
    root.put("x/y", "slash");
    root.put("x~y", "tilde");
    root.put("scalar", "text");
    return root;
  }

  private static Object find(String pointer) {
    return JsonPointer.findByPointer(pointer, DOCUMENT);
  }

  @Nested
  @DisplayName("resolution")
  class Resolution {

    @Test
    void reads_a_top_level_member() {
      assertThat(find("/scalar")).isEqualTo("text");
    }

    @Test
    void reads_a_nested_member() {
      assertThat(find("/a/b")).isEqualTo(1);
    }

    @Test
    void returns_a_whole_subtree() {
      assertThat(find("/a")).isEqualTo(Map.of("b", 1));
    }

    @Test
    void indexes_into_a_list() {
      assertThat(find("/arr/0")).isEqualTo(10);
      assertThat(find("/arr/1")).isEqualTo(20);
    }

    @Test
    void addresses_a_member_whose_name_is_empty() {
      assertThat(find("/")).isEqualTo("empty-key");
    }

    @Test
    void a_null_member_resolves_to_null() {
      assertThat(find("/n")).isNull();
      assertThat(find("/nulls/1")).isNull();
    }
  }

  @Nested
  @DisplayName("escaping")
  class Escaping {

    @Test
    void tilde_one_is_a_slash() {
      assertThat(find("/x~1y")).isEqualTo("slash");
    }

    @Test
    void tilde_zero_is_a_tilde() {
      assertThat(find("/x~0y")).isEqualTo("tilde");
    }

    @Test
    void tilde_zero_one_is_a_literal_tilde_one() {
      // Un-escaping ~0 first would turn this into "/" instead.
      Map<String, Object> document = Map.of("~1", "literal");

      assertThat(JsonPointer.findByPointer("/~01", document)).isEqualTo("literal");
    }
  }

  @Nested
  @DisplayName("misses")
  class Misses {

    @Test
    void an_absent_member_is_null() {
      assertThat(find("/missing")).isNull();
      assertThat(find("/a/missing")).isNull();
    }

    @Test
    void an_out_of_range_index_is_null() {
      assertThat(find("/arr/2")).isNull();
      assertThat(find("/arr/99")).isNull();
    }

    @Test
    void a_negative_index_is_null() {
      // Java's own negative indexing must not leak into RFC 6901's unsigned indexes.
      assertThat(find("/arr/-1")).isNull();
    }

    @Test
    void a_non_numeric_index_is_null() {
      assertThat(find("/arr/x")).isNull();
    }

    @Test
    void stepping_into_a_scalar_is_null() {
      assertThat(find("/scalar/deeper")).isNull();
      assertThat(find("/a/b/c")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a/b", "relative", "#/a"})
    void a_pointer_that_does_not_start_with_a_slash_is_null(String pointer) {
      assertThat(find(pointer)).isNull();
    }

    @Test
    void a_null_pointer_or_document_is_null() {
      assertThat(JsonPointer.findByPointer(null, DOCUMENT)).isNull();
      assertThat(JsonPointer.findByPointer("/a", null)).isNull();
    }
  }
}
