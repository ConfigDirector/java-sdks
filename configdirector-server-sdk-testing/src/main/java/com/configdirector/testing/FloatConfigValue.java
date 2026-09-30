package com.configdirector.testing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Seeds a float config into the test client {@link ConfigDirectorTestExtension} creates for a test.
 *
 * <p>On a test class it applies to every test in the class; on a test method it adds to the
 * class's values, or overrides the one with the same key.
 *
 * <pre>{@code
 * @Test
 * @FloatConfigValue(key = "example", value = 2.5)
 * void reads_the_value(TestClient testClient) { ... }
 * }</pre>
 */
@Documented
@Repeatable(FloatConfigValues.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FloatConfigValue {

  /**
   * The config key.
   *
   * @return the key, not blank
   */
  String key();

  /**
   * The value.
   *
   * @return the value to serve, which must be finite
   */
  double value();
}
