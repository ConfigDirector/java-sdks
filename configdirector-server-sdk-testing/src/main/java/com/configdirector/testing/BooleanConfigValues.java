package com.configdirector.testing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Holds repeated {@link BooleanConfigValue} annotations. Repeat the annotation itself rather than
 * using this container directly.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface BooleanConfigValues {

  /**
   * The repeated annotations.
   *
   * @return each config value, in declaration order
   */
  BooleanConfigValue[] value();
}
