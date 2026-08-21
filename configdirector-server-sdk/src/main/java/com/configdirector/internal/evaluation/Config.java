package com.configdirector.internal.evaluation;

import com.configdirector.ConfigType;
import java.util.List;

public record Config(
    String id,
    String key,
    ConfigType type,
    TargetingRules target,
    List<Variation> variations,
    Object typeConstraints) {

  public Config {
    variations = variations == null ? List.of() : List.copyOf(variations);
  }
}
