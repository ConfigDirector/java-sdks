package com.configdirector.samples.openfeature.micronaut;

import io.micronaut.runtime.Micronaut;

public class Application {

  public static void main(String[] args) {
    Micronaut.build(args)
        .mainClass(Application.class)
        .propertySources(DotEnvPropertySource.load(".env"))
        .start();
  }
}
