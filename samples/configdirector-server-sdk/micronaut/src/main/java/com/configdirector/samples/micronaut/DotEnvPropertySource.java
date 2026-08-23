package com.configdirector.samples.micronaut;

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

/**
 * A {@code .env} file, read as if its contents were environment variables.
 *
 * <p>Micronaut has no {@code .env} format of its own, and this sample adds no library for one.
 * What it does have is {@link PropertyConvention#ENVIRONMENT_VARIABLE}: declare that convention
 * and Micronaut applies the same name mangling it applies to the real environment, so
 * {@code CONFIGDIRECTOR_SERVER_KEY} in the file resolves {@code configdirector.server-key} exactly
 * as the exported variable would.
 *
 * <p>The order puts this between application.properties ({@code -300}) and the real environment
 * ({@code -200}), so the file overrides the defaults baked into application.properties while a
 * real environment variable still overrides the file. That is the precedence you want in
 * production, where the platform injects secrets rather than shipping a {@code .env}.
 */
final class DotEnvPropertySource extends MapPropertySource {

  private static final int ORDER = EnvironmentPropertySource.POSITION - 50;

  private DotEnvPropertySource(Map<String, Object> values) {
    super("dotenv", values);
  }

  /**
   * Reads {@code path} relative to the working directory. {@code run} sets that to this module, so
   * the file belongs at samples/configdirector-server-sdk/micronaut/.env. An absent file is not an
   * error; the app starts on the defaults in application.properties.
   */
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
