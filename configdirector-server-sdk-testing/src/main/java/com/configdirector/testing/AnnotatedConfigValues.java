package com.configdirector.testing;

import java.lang.reflect.AnnotatedElement;
import org.junit.platform.commons.support.AnnotationSupport;

final class AnnotatedConfigValues {

  private AnnotatedConfigValues() {}

  static void seed(TestClient testClient, AnnotatedElement... elements) {
    for (AnnotatedElement element : elements) {
      seed(testClient, element);
    }
  }

  private static void seed(TestClient testClient, AnnotatedElement element) {
    for (BooleanConfigValue config : AnnotationSupport.findRepeatableAnnotations(element, BooleanConfigValue.class)) {
      testClient.setValue(config.key(), config.value());
    }
    for (IntegerConfigValue config : AnnotationSupport.findRepeatableAnnotations(element, IntegerConfigValue.class)) {
      testClient.setValue(config.key(), config.value());
    }
    for (FloatConfigValue config : AnnotationSupport.findRepeatableAnnotations(element, FloatConfigValue.class)) {
      testClient.setValue(config.key(), config.value());
    }
    for (StringConfigValue config : AnnotationSupport.findRepeatableAnnotations(element, StringConfigValue.class)) {
      testClient.setValue(config.key(), config.value());
    }
    for (JsonConfigValue config : AnnotationSupport.findRepeatableAnnotations(element, JsonConfigValue.class)) {
      testClient.setJsonValue(config.key(), config.value());
    }
  }
}
