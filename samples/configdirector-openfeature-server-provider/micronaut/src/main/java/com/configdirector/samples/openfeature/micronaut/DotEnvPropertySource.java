package com.configdirector.samples.openfeature.micronaut;

import io.micronaut.context.env.EnvironmentPropertySource;
import io.micronaut.context.env.MapPropertySource;
import io.micronaut.context.env.PropertySource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DotEnvPropertySource extends MapPropertySource {

  private static final int ORDER = EnvironmentPropertySource.POSITION - 50;

  private DotEnvPropertySource(Map<String, Object> values) {
    super("dotenv", values);
  }

  static PropertySource load(String path) {
    Path file = Path.of(path);
    if (!Files.isReadable(file)) {
      return new DotEnvPropertySource(Map.of());
    }

    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + file.toAbsolutePath(), e);
    }

    Map<String, Object> values = new LinkedHashMap<>();
    for (String line : lines) {
      String entry = line.strip();
      if (entry.isEmpty() || entry.startsWith("#")) {
        continue;
      }
      int separator = entry.indexOf('=');
      if (separator <= 0) {
        continue;
      }
      String name = entry.substring(0, separator).strip();
      values.put(name, unquote(entry.substring(separator + 1).strip()));
    }
    return new DotEnvPropertySource(values);
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && (value.startsWith("\"") && value.endsWith("\"")
            || value.startsWith("'") && value.endsWith("'"))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public PropertyConvention getConvention() {
    return PropertyConvention.ENVIRONMENT_VARIABLE;
  }
}
