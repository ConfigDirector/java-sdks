package com.configdirector.internal.client;

import com.configdirector.ConfigDirectorInitializationException;
import com.configdirector.ConfigDirectorValidationException;
import com.configdirector.internal.evaluation.Config;
import com.configdirector.internal.value.ValueParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

sealed interface OfflineSource {

  Map<String, Config> loadConfigs();

  record InCodeDefaults() implements OfflineSource {

    @Override
    public Map<String, Config> loadConfigs() {
      return Map.of();
    }
  }

  record ValuesFile(Path path) implements OfflineSource {

    @Override
    public Map<String, Config> loadConfigs() {
      return configsOf(valuesIn(path), "The offline values file '" + path + "'");
    }
  }

  record InlineValues(Map<String, Object> values) implements OfflineSource {

    @Override
    public Map<String, Config> loadConfigs() {
      return configsOf(values, "The offline values");
    }
  }

  record Rejected(String message) implements OfflineSource {

    @Override
    public Map<String, Config> loadConfigs() {
      throw new ConfigDirectorInitializationException(message);
    }
  }

  private static Map<String, Object> valuesIn(Path path) {
    String text;
    try {
      text = Files.readString(path);
    } catch (IOException unreadable) {
      throw new ConfigDirectorInitializationException(
          "The offline values file '" + path + "' could not be read: " + unreadable, unreadable);
    }
    JsonElement document;
    try {
      document = StrictJson.parse(text);
    } catch (JsonParseException malformed) {
      throw new ConfigDirectorInitializationException(
          "The offline values file '" + path + "' is not valid JSON: " + malformed.getMessage(),
          malformed);
    }
    if (!document.isJsonObject()) {
      throw new ConfigDirectorInitializationException(
          "The offline values file '"
              + path
              + "' must hold a JSON object with one value per config key.");
    }
    Map<String, Object> values = new LinkedHashMap<>();
    document
        .getAsJsonObject()
        .entrySet()
        .forEach(entry -> values.put(entry.getKey(), ValueParser.toJava(entry.getValue())));
    return values;
  }

  private static Map<String, Config> configsOf(
      Map<String, ?> values, String sourceDescription) {
    Map<String, Config> configs = new LinkedHashMap<>();
    try {
      values.forEach(
          (key, value) ->
              configs.put(key, NativeValueEncoder.encode(key, value, "offline:" + key, null)));
    } catch (ConfigDirectorValidationException refused) {
      throw new ConfigDirectorInitializationException(
          sourceDescription + " could not be loaded. " + refused.getMessage(), refused);
    }
    return configs;
  }
}
