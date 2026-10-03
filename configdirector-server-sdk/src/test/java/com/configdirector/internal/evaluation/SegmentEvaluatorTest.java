package com.configdirector.internal.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigType;
import com.configdirector.Context;
import com.configdirector.Metadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SegmentEvaluatorTest {

  private static final String ACME = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String BETA = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final String MISSING = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

  private final ConfigEvaluator evaluator = new ConfigEvaluator();

  private static AttributeCondition emailEndsWith(String domain) {
    return new AttributeCondition(
        "g", "traits", "ends with any of", "text", List.of(domain), "/email");
  }

  private static AttributeCondition planIs(String plan, String operator) {
    return new AttributeCondition("g", "traits", operator, "text", List.of(plan), "/plan");
  }

  private static SegmentCondition inSegment(String segmentId, String operator) {
    return new SegmentCondition("c", operator, segmentId);
  }

  private static Config configServing(String value, Condition... conditions) {
    return new Config(
        "config-1",
        "greeting",
        ConfigType.STRING,
        new TargetingRules(
            "hello",
            null,
            List.of(
                new ConditionalRule(
                    "r", 0, List.of(conditions), "value", value, "value-id", List.of()))),
        List.of(),
        null);
  }

  private static EvaluationContext withTraits(Map<String, Object> traits) {
    return new EvaluationContext(Context.builder().id("u1").traits(traits).build(), null);
  }

  private static final Map<String, Segment> ACME_MEMBERS =
      Map.of(ACME, new Segment(List.of(List.of(emailEndsWith("@acme.com")))));

  private String servedTo(Config config, Map<String, Segment> segments, Map<String, Object> traits) {
    return evaluator.evaluate(config, withTraits(traits), segments).value();
  }

  @Nested
  @DisplayName("segment conditions")
  class SegmentConditions {

    @Test
    void in_serves_a_context_in_the_segment() {
      Config config = configServing("members", inSegment(ACME, "in"));

      assertThat(servedTo(config, ACME_MEMBERS, Map.of("email", "ann@acme.com"))).isEqualTo("members");
    }

    @Test
    void in_falls_through_for_a_context_outside_the_segment() {
      Config config = configServing("members", inSegment(ACME, "in"));

      assertThat(servedTo(config, ACME_MEMBERS, Map.of("email", "bob@other.com"))).isEqualTo("hello");
    }

    @Test
    void not_in_serves_a_context_outside_the_segment() {
      Config config = configServing("outsiders", inSegment(ACME, "not in"));

      assertThat(servedTo(config, ACME_MEMBERS, Map.of("email", "bob@other.com")))
          .isEqualTo("outsiders");
    }

    @Test
    void not_in_falls_through_for_a_context_in_the_segment() {
      Config config = configServing("outsiders", inSegment(ACME, "not in"));

      assertThat(servedTo(config, ACME_MEMBERS, Map.of("email", "ann@acme.com"))).isEqualTo("hello");
    }

    @Test
    void operators_are_matched_case_insensitively() {
      Config config = configServing("members", inSegment(ACME, "IN"));

      assertThat(servedTo(config, ACME_MEMBERS, Map.of("email", "ann@acme.com"))).isEqualTo("members");
    }

    @Test
    void an_unknown_segment_operator_never_matches() {
      Config config = configServing("members", inSegment(ACME, "within"));

      assertThat(servedTo(config, ACME_MEMBERS, Map.of("email", "ann@acme.com"))).isEqualTo("hello");
    }

    @Test
    void a_segment_absent_from_the_map_matches_nothing_for_in() {
      Config config = configServing("members", inSegment(MISSING, "in"));

      assertThat(servedTo(config, ACME_MEMBERS, Map.of("email", "ann@acme.com"))).isEqualTo("hello");
    }

    @Test
    void a_segment_absent_from_the_map_matches_nothing_for_not_in() {
      Config config = configServing("outsiders", inSegment(MISSING, "not in"));

      assertThat(servedTo(config, ACME_MEMBERS, Map.of("email", "ann@acme.com"))).isEqualTo("hello");
    }

    @Test
    void a_segment_condition_never_matches_without_a_segments_map() {
      Config config = configServing("outsiders", inSegment(ACME, "not in"));

      assertThat(evaluator.evaluate(config, withTraits(Map.of("email", "bob@other.com"))).value())
          .isEqualTo("hello");
    }

    @Test
    void combines_with_attribute_conditions_by_and() {
      Config config = configServing("pro members", inSegment(ACME, "in"), planIs("pro", "equals"));

      assertThat(servedTo(config, ACME_MEMBERS, Map.of("email", "ann@acme.com", "plan", "pro")))
          .isEqualTo("pro members");
      assertThat(servedTo(config, ACME_MEMBERS, Map.of("email", "ann@acme.com", "plan", "free")))
          .isEqualTo("hello");
      assertThat(servedTo(config, ACME_MEMBERS, Map.of("email", "bob@other.com", "plan", "pro")))
          .isEqualTo("hello");
    }
  }

  @Nested
  @DisplayName("membership")
  class Membership {

    @Test
    void any_group_matching_puts_the_context_in() {
      Map<String, Segment> segments =
          Map.of(
              ACME,
              new Segment(
                  List.of(
                      List.of(emailEndsWith("@acme.com"), planIs("pro", "equals")),
                      List.of(emailEndsWith("@beta.com")))));
      Config config = configServing("members", inSegment(ACME, "in"));

      assertThat(servedTo(config, segments, Map.of("email", "ann@acme.com", "plan", "pro")))
          .isEqualTo("members");
      assertThat(servedTo(config, segments, Map.of("email", "ann@acme.com", "plan", "free")))
          .isEqualTo("hello");
      assertThat(servedTo(config, segments, Map.of("email", "cat@beta.com", "plan", "free")))
          .isEqualTo("members");
      assertThat(servedTo(config, segments, Map.of("email", "bob@other.com", "plan", "pro")))
          .isEqualTo("hello");
    }

    @Test
    void a_group_with_no_conditions_matches_every_context() {
      Config config = configServing("everyone", inSegment(ACME, "in"));

      assertThat(servedTo(config, Map.of(ACME, new Segment(List.of(List.of()))), Map.of()))
          .isEqualTo("everyone");
    }

    @Test
    void a_segment_with_no_groups_matches_no_context() {
      Config config = configServing("nobody", inSegment(BETA, "in"));

      assertThat(servedTo(config, Map.of(BETA, new Segment(List.of())), Map.of())).isEqualTo("hello");
    }

    @Test
    void an_absent_trait_is_the_empty_string_inside_a_group() {
      Map<String, Segment> segments =
          Map.of(ACME, new Segment(List.of(List.of(planIs("free", "is NOT one of")))));
      Config config = configServing("not free", inSegment(ACME, "in"));

      assertThat(servedTo(config, segments, Map.of())).isEqualTo("not free");
    }

    @Test
    void groups_see_the_metadata_of_the_evaluation() {
      AttributeCondition versionAtLeast2 =
          new AttributeCondition("g", "appVersion", ">=", "semver", List.of("2.0.0"), null);
      Map<String, Segment> segments = Map.of(ACME, new Segment(List.of(List.of(versionAtLeast2))));
      Config config = configServing("modern", inSegment(ACME, "in"));
      EvaluationContext modern =
          new EvaluationContext(Context.builder().id("u1").build(), new Metadata(null, "2.1.0"));
      EvaluationContext old =
          new EvaluationContext(Context.builder().id("u1").build(), new Metadata(null, "1.9.0"));

      assertThat(evaluator.evaluate(config, modern, segments).value()).isEqualTo("modern");
      assertThat(evaluator.evaluate(config, old, segments).value()).isEqualTo("hello");
    }
  }
}
