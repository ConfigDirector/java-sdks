package com.configdirector.internal.eventsource;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

// Not thread safe. One parser belongs to one connection, and only its reader touches it.
public final class EventSourceParser {

  public static final int DEFAULT_MAX_LINE_CHARS = 1 << 20;
  public static final int DEFAULT_MAX_EVENT_CHARS = 1 << 24;

  private static final char BYTE_ORDER_MARK = 0xFEFF;

  // Some servers emit the mark's UTF-8 bytes without them being decoded as one character.
  private static final char[] UNDECODED_BYTE_ORDER_MARK = {0xEF, 0xBB, 0xBF};

  private final Consumer<EventSourceMessage> onEvent;
  private final Consumer<String> onComment;
  private final IntConsumer onRetry;
  private final int maxLineChars;
  private final int maxEventChars;

  private boolean firstChunk = true;
  private final StringBuilder line = new StringBuilder();
  private boolean pendingLineFeed;

  private String eventType;
  private final StringBuilder data = new StringBuilder();
  private int dataLines;
  private String lastEventId;

  private EventSourceParser(Builder builder) {
    this.onEvent = builder.onEvent;
    this.onComment = builder.onComment;
    this.onRetry = builder.onRetry;
    this.maxLineChars = builder.maxLineChars;
    this.maxEventChars = builder.maxEventChars;
  }

  public static Builder builder() {
    return new Builder();
  }

  public void feed(String chunk) {
    feed(chunk.toCharArray(), 0, chunk.length());
  }

  public void feed(char[] chunk, int offset, int length) {
    int start = offset;
    int end = offset + length;

    if (firstChunk) {
      firstChunk = false;
      start = skipByteOrderMark(chunk, start, end);
    }
    if (start >= end) {
      return;
    }
    if (pendingLineFeed) {
      pendingLineFeed = false;
      if (chunk[start] == '\n') {
        start++;
      }
    }

    int lineStart = start;
    for (int index = start; index < end; index++) {
      char character = chunk[index];
      if (character != '\r' && character != '\n') {
        continue;
      }

      finishLine(chunk, lineStart, index);
      if (character == '\r') {
        if (index + 1 < end) {
          if (chunk[index + 1] == '\n') {
            index++;
          }
        } else {
          pendingLineFeed = true;
        }
      }
      lineStart = index + 1;
    }

    if (lineStart < end) {
      bufferLine(chunk, lineStart, end - lineStart);
    }
  }

  // An event needs a terminating blank line, so anything still buffered is discarded.
  public void finish() {
    line.setLength(0);
    pendingLineFeed = false;
    resetEvent();
  }

  private static int skipByteOrderMark(char[] chunk, int start, int end) {
    if (start >= end) {
      return start;
    }
    if (chunk[start] == BYTE_ORDER_MARK) {
      return start + 1;
    }
    if (end - start >= UNDECODED_BYTE_ORDER_MARK.length
        && chunk[start] == UNDECODED_BYTE_ORDER_MARK[0]
        && chunk[start + 1] == UNDECODED_BYTE_ORDER_MARK[1]
        && chunk[start + 2] == UNDECODED_BYTE_ORDER_MARK[2]) {
      return start + UNDECODED_BYTE_ORDER_MARK.length;
    }
    return start;
  }

  private void bufferLine(char[] chunk, int from, int count) {
    if ((long) line.length() + count > maxLineChars) {
      throw new StreamTooLargeException(
          "A single line exceeded " + maxLineChars + " characters without a terminator");
    }
    line.append(chunk, from, count);
  }

  private void finishLine(char[] chunk, int from, int to) {
    String completed;
    if (line.isEmpty()) {
      completed = new String(chunk, from, to - from);
    } else {
      bufferLine(chunk, from, to - from);
      completed = line.toString();
      line.setLength(0);
    }
    dispatchLine(completed);
  }

  private void dispatchLine(String text) {
    if (text.isEmpty()) {
      emitEvent();
      return;
    }
    if (text.charAt(0) == ':') {
      if (onComment != null) {
        onComment.accept(fieldValue(text, 1));
      }
      return;
    }

    int colon = text.indexOf(':');
    if (colon < 0) {
      applyField(text, "");
    } else {
      applyField(text.substring(0, colon), fieldValue(text, colon + 1));
    }
  }

  private void applyField(String field, String value) {
    switch (field) {
      case "event" -> eventType = value;
      case "data" -> appendData(value);
      case "id" -> {
        if (value.indexOf('\0') < 0) {
          lastEventId = value;
        }
      }
      case "retry" -> applyRetry(value);
      default -> {
        // Unknown fields are ignored, which is how the spec stays extensible.
      }
    }
  }

  private void appendData(String value) {
    if ((long) data.length() + value.length() + 1 > maxEventChars) {
      throw new StreamTooLargeException(
          "A single event exceeded " + maxEventChars + " characters of data");
    }
    // The spec appends the value then a LF and drops the trailing LF when dispatching; joining
    // with a separator is equivalent without rebuilding the string on every data line.
    if (dataLines > 0) {
      data.append('\n');
    }
    data.append(value);
    dataLines++;
  }

  private void applyRetry(String value) {
    if (onRetry == null || !isAsciiDigits(value)) {
      return;
    }
    try {
      onRetry.accept(Integer.parseInt(value));
    } catch (NumberFormatException overflow) {
      // All digits, so only a number too large for an int reaches here. The spec's remedy for a
      // value it cannot use is to ignore it.
    }
  }

  private void emitEvent() {
    if (!data.isEmpty() && onEvent != null) {
      onEvent.accept(new EventSourceMessage(data.toString(), eventType, lastEventId));
    }
    resetEvent();
  }

  private void resetEvent() {
    eventType = null;
    data.setLength(0);
    dataLines = 0;
  }

  // Character.isDigit accepts Unicode digits that Integer.parseInt then rejects.
  private static boolean isAsciiDigits(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < '0' || character > '9') {
        return false;
      }
    }
    return true;
  }

  // A single space after the colon is part of the delimiter, not of the value.
  private static String fieldValue(String text, int start) {
    if (start < text.length() && text.charAt(start) == ' ') {
      return text.substring(start + 1);
    }
    return text.substring(start);
  }

  public static final class Builder {

    private Consumer<EventSourceMessage> onEvent;
    private Consumer<String> onComment;
    private IntConsumer onRetry;
    private int maxLineChars = DEFAULT_MAX_LINE_CHARS;
    private int maxEventChars = DEFAULT_MAX_EVENT_CHARS;

    private Builder() {}

    public Builder onEvent(Consumer<EventSourceMessage> handler) {
      this.onEvent = handler;
      return this;
    }

    public Builder onComment(Consumer<String> handler) {
      this.onComment = handler;
      return this;
    }

    // Receives the server's requested reconnect delay, in milliseconds.
    public Builder onRetry(IntConsumer handler) {
      this.onRetry = handler;
      return this;
    }

    public Builder maxLineChars(int limit) {
      this.maxLineChars = limit;
      return this;
    }

    public Builder maxEventChars(int limit) {
      this.maxEventChars = limit;
      return this;
    }

    public EventSourceParser build() {
      return new EventSourceParser(this);
    }
  }
}
