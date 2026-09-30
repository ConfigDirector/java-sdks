package com.configdirector.testing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Seeds a JSON config into the test client {@link ConfigDirectorTestExtension} creates for a test.
 *
 * <p>On a test class it applies to every test in the class; on a test method it adds to the
 * class's values, or overrides the one with the same key.
 *
 * <pre>{@code
 * @Test
 * @JsonConfigValue(key = "example", value = "{\"color\": \"blue\"}")
 * void reads_the_value(TestClient testClient) { ... }
 * }</pre>
 */
@Documented
@Repeatable(JsonConfigValues.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface JsonConfigValue {

  /**
   * The config key.
   *
   * @return the key, not blank
   */
  String key();

  /**
   * The value.
   *
   * @return strict JSON text holding an object or an array
   */
  String value();
}
