package com.configdirector.internal.eventsource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventSourceParserTest {

  private final List<EventSourceMessage> events = new ArrayList<>();
  private final List<String> comments = new ArrayList<>();
  private final List<Integer> retries = new ArrayList<>();

  private EventSourceParser parser() {
    return EventSourceParser.builder()
        .onEvent(events::add)
        .onComment(comments::add)
        .onRetry(retries::add)
        .build();
  }

  /** Feeds the whole input as one chunk, which is the common case. */
  private void parse(String input) {
    parser().feed(input);
  }

  private EventSourceMessage onlyEvent() {
    assertThat(events).hasSize(1);
    return events.get(0);
  }

  @Nested
  @DisplayName("event dispatching")
  class EventDispatching {

    @Test
    void dispatches_an_event_on_the_blank_line_after_data() {
      parse("data: hello\n\n");

      assertThat(onlyEvent().data()).isEqualTo("hello");
    }

    @Test
    void does_not_dispatch_before_the_blank_line() {
      parse("data: hello\n");

      assertThat(events).isEmpty();
    }

    @Test
    void does_not_dispatch_a_blank_line_with_no_data() {
      parse("\n\n\n");

      assertThat(events).isEmpty();
    }

    @Test
    void does_not_dispatch_when_only_id_and_type_are_set() {
      parse("id: 1\nevent: update\n\n");

      assertThat(events).isEmpty();
    }

    @Test
    void resets_the_data_after_dispatching() {
      parse("data: one\n\ndata: two\n\n");

      assertThat(events).extracting(EventSourceMessage::data).containsExactly("one", "two");
    }

    @Test
    void resets_the_event_type_after_dispatching() {
      parse("event: update\ndata: one\n\ndata: two\n\n");

      assertThat(events).extracting(EventSourceMessage::type).containsExactly("update", null);
    }

    @Test
    void carries_the_last_event_id_to_later_events() {
      parse("id: 7\ndata: one\n\ndata: two\n\n");

      assertThat(events).extracting(EventSourceMessage::id).containsExactly("7", "7");
    }
  }

  @Nested
  @DisplayName("the data field")
  class DataField {

    @Test
    void strips_a_single_leading_space() {
      parse("data: hello\n\n");

      assertThat(onlyEvent().data()).isEqualTo("hello");
    }

    @Test
    void does_not_strip_a_second_leading_space() {
      parse("data:  hello\n\n");

      assertThat(onlyEvent().data()).isEqualTo(" hello");
    }

    @Test
    void accepts_no_space_after_the_colon() {
      parse("data:hello\n\n");

      assertThat(onlyEvent().data()).isEqualTo("hello");
    }

    @Test
    void joins_multiple_data_lines_with_newlines() {
      parse("data: one\ndata: two\ndata: three\n\n");

      assertThat(onlyEvent().data()).isEqualTo("one\ntwo\nthree");
    }

    @Test
    void an_empty_data_line_contributes_a_newline() {
      parse("data:\ndata:\n\n");

      assertThat(onlyEvent().data()).isEqualTo("\n");
    }

    @Test
    void a_field_with_no_colon_has_an_empty_value() {
      // "data" alone is a data field whose value is the empty string, so two of them make one
      // newline -- enough to dispatch, where one alone would not be.
      parse("data\ndata\n\n");

      assertThat(onlyEvent().data()).isEqualTo("\n");
    }

    @Test
    void keeps_colons_inside_the_value() {
      parse("data: a:b:c\n\n");

      assertThat(onlyEvent().data()).isEqualTo("a:b:c");
    }
  }

  @Nested
  @DisplayName("the event field")
  class EventTypeField {

    @Test
    void sets_the_event_type() {
      parse("event: update\ndata: hello\n\n");

      assertThat(onlyEvent().type()).isEqualTo("update");
    }

    @Test
    void the_last_event_field_wins() {
      parse("event: first\nevent: second\ndata: hello\n\n");

      assertThat(onlyEvent().type()).isEqualTo("second");
    }
  }

  @Nested
  @DisplayName("the id field")
  class IdField {

    @Test
    void sets_the_event_id() {
      parse("id: 42\ndata: hello\n\n");

      assertThat(onlyEvent().id()).isEqualTo("42");
    }

    @Test
    void ignores_an_id_containing_a_null_character() {
      parse("id: abc\0def\ndata: hello\n\n");

      assertThat(onlyEvent().id()).isNull();
    }

    @Test
    void accepts_an_empty_id() {
      parse("id: 1\n\nid:\ndata: hello\n\n");

      assertThat(onlyEvent().id()).isEmpty();
    }
  }

  @Nested
  @DisplayName("the retry field")
  class RetryField {

    @Test
    void reports_an_integer_retry() {
      parse("retry: 5000\ndata: hello\n\n");

      assertThat(retries).containsExactly(5000);
    }

    @Test
    void ignores_a_retry_that_is_not_a_plain_integer() {
      parse("retry: 1.5\nretry: soon\nretry: -1\nretry:\ndata: hello\n\n");

      assertThat(retries).isEmpty();
    }

    @Test
    void ignores_non_ascii_digits() {
      // Arabic-Indic digits are digits to Character.isDigit but not to Integer.parseInt.
      parse("retry: ٠١\ndata: hello\n\n");

      assertThat(retries).isEmpty();
    }

    @Test
    void ignores_a_retry_too_large_for_an_int() {
      parse("retry: 99999999999999\ndata: hello\n\n");

      assertThat(retries).isEmpty();
      assertThat(events).hasSize(1);
    }

    @Test
    void a_retry_alone_does_not_dispatch_an_event() {
      parse("retry: 5000\n\n");

      assertThat(retries).containsExactly(5000);
      assertThat(events).isEmpty();
    }
  }

  @Nested
  @DisplayName("comments")
  class Comments {

    @Test
    void reports_a_comment() {
      parse(": keepalive\n");

      assertThat(comments).containsExactly("keepalive");
    }

    @Test
    void reports_an_empty_comment() {
      parse(":\n");

      assertThat(comments).containsExactly("");
    }

    @Test
    void a_comment_does_not_dispatch_an_event() {
      parse(": keepalive\n\n");

      assertThat(events).isEmpty();
    }

    @Test
    void mixes_comments_and_data_in_one_event() {
      parse("data: one\n: a note\ndata: two\n\n");

      assertThat(onlyEvent().data()).isEqualTo("one\ntwo");
      assertThat(comments).containsExactly("a note");
    }
  }

  @Nested
  @DisplayName("unknown fields")
  class UnknownFields {

    @Test
    void ignores_unknown_field_names() {
      parse("unknown: value\nDATA: wrong case\ndata: hello\n\n");

      assertThat(onlyEvent().data()).isEqualTo("hello");
    }
  }

  @Nested
  @DisplayName("line endings")
  class LineEndings {

    @Test
    void handles_each_terminator() {
      parse("data: lf\n\n");
      parse("data: cr\r\r");
      parse("data: crlf\r\n\r\n");

      assertThat(events).extracting(EventSourceMessage::data).containsExactly("lf", "cr", "crlf");
    }

    @Test
    void handles_mixed_terminators_in_one_chunk() {
      parse("data: one\r\ndata: two\rdata: three\n\n");

      assertThat(onlyEvent().data()).isEqualTo("one\ntwo\nthree");
    }

    @Test
    void a_crlf_split_across_chunks_is_one_terminator() {
      EventSourceParser parser = parser();
      parser.feed("data: hello\r");
      parser.feed("\n\r\n");

      assertThat(onlyEvent().data()).isEqualTo("hello");
    }

    @Test
    void a_lone_cr_at_the_end_of_a_chunk_still_terminates_its_line() {
      EventSourceParser parser = parser();
      parser.feed("data: hello\r");
      parser.feed("data: world\n\n");

      assertThat(onlyEvent().data()).isEqualTo("hello\nworld");
    }
  }

  @Nested
  @DisplayName("the byte order mark")
  class ByteOrderMark {

    @Test
    void strips_a_leading_byte_order_mark() {
      parse("﻿data: hello\n\n");

      assertThat(onlyEvent().data()).isEqualTo("hello");
    }

    @Test
    void strips_an_undecoded_utf8_byte_order_mark() {
      parse("ï»¿data: hello\n\n");

      assertThat(onlyEvent().data()).isEqualTo("hello");
    }

    @Test
    void does_not_strip_a_mark_that_is_not_at_the_start() {
      parse("data: ﻿hello\n\n");

      assertThat(onlyEvent().data()).isEqualTo("﻿hello");
    }

    @Test
    void only_the_very_first_chunk_can_carry_one() {
      // A later chunk's mark is ordinary text, so it becomes part of an unknown field name and
      // the line contributes nothing.
      EventSourceParser parser = parser();
      parser.feed("data: one\n\n");
      parser.feed("﻿data: two\n\n");

      assertThat(events).extracting(EventSourceMessage::data).containsExactly("one");
    }
  }

  @Nested
  @DisplayName("chunked input")
  class ChunkedInput {

    @Test
    void handles_a_field_split_across_chunks() {
      EventSourceParser parser = parser();
      parser.feed("da");
      parser.feed("ta: hel");
      parser.feed("lo\n\n");

      assertThat(onlyEvent().data()).isEqualTo("hello");
    }

    @Test
    void handles_the_delimiter_split_across_chunks() {
      EventSourceParser parser = parser();
      parser.feed("data: hello\n");
      parser.feed("\n");

      assertThat(onlyEvent().data()).isEqualTo("hello");
    }

    @Test
    void handles_several_events_in_one_chunk() {
      parse("data: one\n\ndata: two\n\ndata: three\n\n");

      assertThat(events)
          .extracting(EventSourceMessage::data)
          .containsExactly("one", "two", "three");
    }

    @Test
    void handles_one_character_at_a_time() {
      EventSourceParser parser = parser();
      for (char character : "event: tick\ndata: hello\n\n".toCharArray()) {
        parser.feed(String.valueOf(character));
      }

      assertThat(onlyEvent().data()).isEqualTo("hello");
      assertThat(onlyEvent().type()).isEqualTo("tick");
    }

    @Test
    void ignores_an_empty_chunk() {
      EventSourceParser parser = parser();
      parser.feed("data: hello\n");
      parser.feed("");
      parser.feed("\n");

      assertThat(onlyEvent().data()).isEqualTo("hello");
    }

    @Test
    void honours_the_offset_and_length() {
      char[] backing = "XXdata: hello\n\nXX".toCharArray();
      parser().feed(backing, 2, backing.length - 4);

      assertThat(onlyEvent().data()).isEqualTo("hello");
    }
  }

  @Nested
  @DisplayName("finish")
  class Finish {

    @Test
    void discards_an_event_with_no_terminating_blank_line() {
      EventSourceParser parser = parser();
      parser.feed("data: hello");
      parser.finish();

      assertThat(events).isEmpty();
    }

    @Test
    void discards_an_event_ending_on_a_single_newline() {
      EventSourceParser parser = parser();
      parser.feed("data: hello\n");
      parser.finish();

      assertThat(events).isEmpty();
    }

    @Test
    void does_not_redispatch_a_completed_event() {
      EventSourceParser parser = parser();
      parser.feed("data: hello\n\n");
      parser.finish();

      assertThat(events).hasSize(1);
    }
  }

  @Nested
  @DisplayName("size limits")
  class Limits {

    @Test
    void rejects_a_line_that_never_terminates() {
      EventSourceParser parser =
          EventSourceParser.builder().onEvent(events::add).maxLineChars(64).build();

      assertThatExceptionOfType(StreamTooLargeException.class)
          .isThrownBy(
              () -> {
                for (int i = 0; i < 100; i++) {
                  parser.feed("data: aaaaaaaaaa");
                }
              })
          .withMessageContaining("64");
    }

    @Test
    void rejects_an_event_whose_data_never_ends() {
      EventSourceParser parser =
          EventSourceParser.builder().onEvent(events::add).maxEventChars(64).build();

      assertThatExceptionOfType(StreamTooLargeException.class)
          .isThrownBy(
              () -> {
                for (int i = 0; i < 100; i++) {
                  parser.feed("data: aaaaaaaaaa\n");
                }
              })
          .withMessageContaining("64");
    }

    @Test
    void a_long_but_bounded_value_is_fine() {
      String value = "a".repeat(500);
      EventSourceParser parser =
          EventSourceParser.builder()
              .onEvent(events::add)
              .maxLineChars(1024)
              .maxEventChars(1024)
              .build();

      parser.feed("data: " + value + "\n\n");

      assertThat(onlyEvent().data()).isEqualTo(value);
    }

    @Test
    void the_caps_reset_between_events() {
      EventSourceParser parser =
          EventSourceParser.builder().onEvent(events::add).maxEventChars(64).build();

      for (int i = 0; i < 20; i++) {
        parser.feed("data: aaaaaaaaaa\n\n");
      }

      assertThat(events).hasSize(20);
    }
  }

  @Nested
  @DisplayName("without handlers")
  class WithoutHandlers {

    @Test
    void parsing_without_handlers_does_not_throw() {
      EventSourceParser parser = EventSourceParser.builder().build();

      assertThatCode(
              () -> {
                parser.feed(": a comment\nretry: 1000\nid: 1\ndata: hello\n\n");
                parser.finish();
              })
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("specification examples")
  class SpecExamples {

    @Test
    void multi_line_data() {
      parse("data: YHOO\ndata: +2\ndata: 10\n\n");

      assertThat(onlyEvent().data()).isEqualTo("YHOO\n+2\n10");
    }

    @Test
    void named_events() {
      parse("event: add\ndata: 73\n\nevent: remove\ndata: 2\n\n");

      assertThat(events)
          .extracting(EventSourceMessage::type, EventSourceMessage::data)
          .containsExactly(tuple("add", "73"), tuple("remove", "2"));
    }

    @Test
    void the_last_event_id_persists_until_reset() {
      parse("id: 1\ndata: one\n\ndata: two\n\nid:\ndata: three\n\n");

      assertThat(events).extracting(EventSourceMessage::id).containsExactly("1", "1", "");
    }
  }

  @Nested
  @DisplayName("edge cases")
  class EdgeCases {

    @Test
    void only_comments() {
      parse(": one\n: two\n: three\n");

      assertThat(comments).containsExactly("one", "two", "three");
      assertThat(events).isEmpty();
    }

    @Test
    void repeated_blank_lines_dispatch_once() {
      parse("data: hello\n\n\n\n\n");

      assertThat(events).hasSize(1);
    }

    @Test
    void json_payloads_survive_intact() {
      String payload = "{\"key\":\"value\",\"nested\":{\"n\":1},\"list\":[1,2,3]}";
      parse("data: " + payload + "\n\n");

      assertThat(onlyEvent().data()).isEqualTo(payload);
    }

    @Test
    void an_astral_character_survives() {
      parse("data: 🚀 launched\n\n");

      assertThat(onlyEvent().data()).isEqualTo("🚀 launched");
    }
  }
}
