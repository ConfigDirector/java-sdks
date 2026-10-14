package com.configdirector.openfeature;

import com.configdirector.Context;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Value;

final class ContextMapper {

  private static final String ID = "id";
  private static final String KIND = "kind";
  private static final String NAME = "name";
  private static final String TRAITS = "traits";
  private static final String ANONYMOUS = "anonymous";

  private ContextMapper() {}

  static Context toContext(EvaluationContext evaluationContext) {
    if (evaluationContext == null) {
      return null;
    }

    Context.Builder builder =
        Context.builder()
            .id(identifier(evaluationContext))
            .kind(kind(evaluationContext.getValue(KIND)))
            .name(text(evaluationContext.getValue(NAME)));

    Value traits = evaluationContext.getValue(TRAITS);
    if (traits != null && traits.isStructure() && !traits.asStructure().isEmpty()) {
      builder.traits(ValueMapper.toJava(traits.asStructure()));
    }

    Value anonymous = evaluationContext.getValue(ANONYMOUS);
    if (anonymous != null && anonymous.isBoolean()) {
      builder.anonymous(anonymous.asBoolean());
    }

    return builder.build();
  }

  private static String identifier(EvaluationContext evaluationContext) {
    String targetingKey = evaluationContext.getTargetingKey();
    return targetingKey != null ? targetingKey : text(evaluationContext.getValue(ID));
  }

  private static String kind(Value value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isString()) {
      return value.asString();
    }
    return typeOf(value) + ":" + ValueMapper.toJava(value);
  }

  private static String typeOf(Value value) {
    if (value.isBoolean()) {
      return "boolean";
    }
    if (value.isNumber()) {
      return "number";
    }
    if (value.isStructure()) {
      return "structure";
    }
    if (value.isList()) {
      return "list";
    }
    if (value.isInstant()) {
      return "instant";
    }
    return "value";
  }

  private static String text(Value value) {
    if (value == null || value.isNull() || value.isStructure() || value.isList()) {
      return null;
    }
    return String.valueOf(ValueMapper.toJava(value));
  }
}
