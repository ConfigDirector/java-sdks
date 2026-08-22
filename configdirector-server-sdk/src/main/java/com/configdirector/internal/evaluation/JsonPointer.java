package com.configdirector.internal.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class JsonPointer {

  // Distinguishes "no such member" from a member whose value is JSON null. An enum rather than a
  // bare Object so the identity comparisons read as deliberate.
  private enum Missing {
    TOKEN
  }

  private JsonPointer() {}

  // Null for a pointer that addresses nothing, which is what findByPointer resolves to. Split
  // once, when the config is parsed: the pointer is a constant of the condition, and resolving it
  // runs on the caller's thread for every condition of every config read.
  static List<String> parse(String pointer) {
    if (pointer == null || pointer.isEmpty() || pointer.charAt(0) != '/') {
      return null;
    }

    List<String> tokens = new ArrayList<>();
    int from = 1;
    while (true) {
      int separator = pointer.indexOf('/', from);
      // A trailing empty token addresses a member whose name is the empty string.
      int end = separator < 0 ? pointer.length() : separator;
      String raw = pointer.substring(from, end);
      // ~1 before ~0, so that "~01" resolves to "~1" rather than to "/".
      tokens.add(raw.indexOf('~') < 0 ? raw : raw.replace("~1", "/").replace("~0", "~"));
      if (separator < 0) {
        return List.copyOf(tokens);
      }
      from = separator + 1;
    }
  }

  static Object findByPath(List<String> path, Object document) {
    if (path == null) {
      return null;
    }

    Object current = document;
    for (String token : path) {
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
