package com.configdirector.internal.evaluation;

import java.util.List;
import java.util.Map;

final class JsonPointer {

  // Distinguishes "no such member" from a member whose value is JSON null. An enum rather than a
  // bare Object so the identity comparisons read as deliberate.
  private enum Missing {
    TOKEN
  }

  private JsonPointer() {}

  static Object findByPointer(String pointer, Object document) {
    if (pointer == null || !pointer.startsWith("/")) {
      return null;
    }

    Object current = document;
    // -1 keeps a trailing empty token, which addresses a member whose name is the empty string.
    for (String rawToken : pointer.substring(1).split("/", -1)) {
      // ~1 before ~0, so that "~01" resolves to "~1" rather than to "/".
      String token = rawToken.replace("~1", "/").replace("~0", "~");
      current = step(current, token);
      if (current == Missing.TOKEN) {
        return null;
      }
    }
    return current;
  }

  private static Object step(Object current, String token) {
    if (current instanceof Map<?, ?> map) {
      Object value = map.get(token);
      return value == null && !map.containsKey(token) ? Missing.TOKEN : value;
    }
    if (current instanceof List<?> list) {
      int index;
      try {
        index = Integer.parseInt(token);
      } catch (NumberFormatException notAnIndex) {
        return Missing.TOKEN;
      }
      // RFC 6901 indexes are unsigned; Java's negative indexing must not leak in.
      if (index < 0 || index >= list.size()) {
        return Missing.TOKEN;
      }
      return list.get(index);
    }
    return Missing.TOKEN;
  }
}
