package com.configdirector.internal.evaluation;

import com.configdirector.Context;
import com.configdirector.Metadata;
import com.configdirector.internal.ContextKinds;

public record EvaluationContext(Context context, Metadata metadata) {

  private static final EvaluationContext EMPTY = new EvaluationContext(null, null);

  public static EvaluationContext empty() {
    return EMPTY;
  }

  public Context contextOrEmpty() {
    return context == null ? Context.empty() : context;
  }

  public String effectiveKind() {
    return ContextKinds.effectiveKindOf(context);
  }

  public Metadata metadataOrEmpty() {
    return metadata == null ? Metadata.empty() : metadata;
  }
}
