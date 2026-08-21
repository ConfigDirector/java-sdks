package com.configdirector.internal.telemetry;

import com.configdirector.internal.evaluation.JsonValues;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

// Serializes byte for byte the way JSON.stringify does in the other ConfigDirector SDKs: nothing
// between the punctuation, keys in insertion order, non-ASCII left alone, and a whole float
// without its fractional part. A value too large to report inline is identified by the digest of
// this text, so the same document has to serialize identically everywhere or one value would be
// counted as two.
public final class TelemetryJson {

  private TelemetryJson() {}

  public static String stringify(Object value) {
    StringBuilder json = new StringBuilder();
    write(json, value);
    return json.toString();
  }

  private static void write(StringBuilder json, Object value) {
    if (value == null) {
      json.append("null");
    } else if (value instanceof Boolean flag) {
      json.append(flag.booleanValue());
    } else if (value instanceof Double || value instanceof Float) {
      double number = ((Number) value).doubleValue();
      // JSON has no way to spell NaN or infinity, and JSON.stringify writes null for both.
      json.append(Double.isFinite(number) ? JsonValues.toJsonString(number) : "null");
    } else if (value instanceof Number number) {
      json.append(number);
    } else if (value instanceof CharSequence text) {
      writeString(json, text.toString());
    } else if (value instanceof Map<?, ?> members) {
      writeObject(json, members);
    } else if (value instanceof Collection<?> items) {
      writeArray(json, items);
    } else {
      // Nothing else has a JSON form. Reporting how the value describes itself still counts the
      // evaluation, which matters more here than the shape of the text.
      writeString(json, String.valueOf(value));
    }
  }

  private static void writeObject(StringBuilder json, Map<?, ?> members) {
    json.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> member : members.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      writeString(json, String.valueOf(member.getKey()));
      json.append(':');
      write(json, member.getValue());
    }
    json.append('}');
  }

  private static void writeArray(StringBuilder json, Collection<?> items) {
    json.append('[');
    boolean first = true;
    for (Object item : items) {
      if (!first) {
        json.append(',');
      }
      first = false;
      write(json, item);
    }
    json.append(']');
  }

  private static void writeString(StringBuilder json, String text) {
    json.append('"');
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      switch (character) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\b' -> json.append("\\b");
        case '\f' -> json.append("\\f");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> {
          if (character < 0x20) {
            json.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
          } else {
            json.append(character);
          }
        }
      }
    }
    json.append('"');
  }
}
