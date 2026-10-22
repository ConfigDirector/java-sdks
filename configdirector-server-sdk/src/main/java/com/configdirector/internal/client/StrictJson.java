package com.configdirector.internal.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import java.io.IOException;
import java.io.StringReader;

final class StrictJson {

  private StrictJson() {}

  static JsonElement parse(String text) {
    try (JsonReader reader = new JsonReader(new StringReader(text))) {
      reader.setStrictness(Strictness.STRICT);
      JsonElement document = JsonParser.parseReader(reader);
      if (!endsAfterTheValue(reader)) {
        throw new JsonSyntaxException("the text continues after the JSON value");
      }
      return document;
    } catch (IOException malformed) {
      throw new JsonSyntaxException(malformed.getMessage(), malformed);
    }
  }

  private static boolean endsAfterTheValue(JsonReader reader) throws IOException {
    try {
      return reader.peek() == JsonToken.END_DOCUMENT;
    } catch (MalformedJsonException trailing) {
      return false;
    }
  }
}
