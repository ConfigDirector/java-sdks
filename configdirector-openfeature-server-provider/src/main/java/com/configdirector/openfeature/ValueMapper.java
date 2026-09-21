package com.configdirector.openfeature;

import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Structure;
import dev.openfeature.sdk.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ValueMapper {

  private ValueMapper() {}

  static Object toJava(Value value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isStructure()) {
      return toJava(value.asStructure());
    }
    if (value.isList()) {
      List<Object> items = new ArrayList<>();
      value.asList().forEach(item -> items.add(toJava(item)));
      return items;
    }
    if (value.isInstant()) {
      return value.asInstant().toString();
    }
    return value.asObject();
  }

  static Map<String, Object> toJava(Structure structure) {
    Map<String, Object> entries = new LinkedHashMap<>();
    structure.asMap().forEach((key, entry) -> entries.put(key, toJava(entry)));
    return entries;
  }

  static Value toValue(Object object) {
    if (object == null) {
      return new Value();
    }
    if (object instanceof Boolean flag) {
      return new Value(flag);
    }
    if (object instanceof String text) {
      return new Value(text);
    }
    if (object instanceof Integer number) {
      return new Value(number);
    }
    if (object instanceof Long number) {
      return number.intValue() == number ? new Value(number.intValue()) : new Value(number);
    }
    if (object instanceof Number number) {
      return new Value(number.doubleValue());
    }
    if (object instanceof Map<?, ?> map) {
      Map<String, Value> attributes = new LinkedHashMap<>();
      map.forEach((key, entry) -> attributes.put(String.valueOf(key), toValue(entry)));
      return new Value(new ImmutableStructure(attributes));
    }
    if (object instanceof List<?> list) {
      List<Value> items = new ArrayList<>(list.size());
      list.forEach(item -> items.add(toValue(item)));
      return new Value(items);
    }
    return new Value(String.valueOf(object));
  }
}
