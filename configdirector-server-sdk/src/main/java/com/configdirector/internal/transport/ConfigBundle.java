package com.configdirector.internal.transport;

import com.configdirector.internal.evaluation.Config;
import com.configdirector.internal.evaluation.Segment;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// timestamp is echoed back on the next poll so the server can answer with a delta. The server may
// omit it, in which case every poll returns a full bundle.
public record ConfigBundle(
    Map<String, Config> configs,
    Map<String, Segment> segments,
    BundleKind kind,
    String environmentId,
    String projectId,
    String timestamp) {

  public enum BundleKind {
    FULL,
    DELTA
  }

  public ConfigBundle {
    configs =
        configs == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(configs));
    segments =
        segments == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(segments));
    kind = kind == null ? BundleKind.FULL : kind;
  }
}
