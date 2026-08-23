package com.configdirector.samples.micronaut;

import io.micronaut.runtime.Micronaut;

public class Application {

  public static void main(String[] args) {
    // Micronaut reads OS environment variables on its own; DotEnvPropertySource adds a file that
    // spells them the same way, for local runs where exporting them is a nuisance.
    Micronaut.build(args)
        .mainClass(Application.class)
        .propertySources(DotEnvPropertySource.load(".env"))
        .start();
  }
}
