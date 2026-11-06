package com.configdirector.internal.telemetry;

import com.configdirector.Context;
import com.configdirector.internal.ContextKinds;

record ReportedContext(String kind, String identifier) {

  private static final ReportedContext NONE = new ReportedContext(null, null);

  static ReportedContext of(Context context) {
    if (context == null || context.id() == null || context.id().isBlank() || context.anonymous()) {
      return NONE;
    }
    return new ReportedContext(ContextKinds.effectiveKindOf(context), context.id());
  }
}
