package com.configdirector.internal;

import com.configdirector.Context;

public final class ContextKinds {

  public static final String DEFAULT_KIND = "user";

  private static final int MAXIMUM_KIND_LENGTH = 64;
  private static final int MAXIMUM_SAMPLE_CODE_POINTS = 128;

  private ContextKinds() {}

  public static String effectiveKindOf(Context context) {
    return context == null || context.kind() == null ? DEFAULT_KIND : context.kind();
  }

  public static boolean isAccepted(Context context) {
    return context == null || context.kind() == null || isValidKind(context.kind());
  }

  public static String sampleOf(String kind) {
    if (kind.codePointCount(0, kind.length()) <= MAXIMUM_SAMPLE_CODE_POINTS) {
      return kind;
    }
    return kind.substring(0, kind.offsetByCodePoints(0, MAXIMUM_SAMPLE_CODE_POINTS));
  }

  private static boolean isValidKind(String kind) {
    if (kind.isEmpty() || kind.length() > MAXIMUM_KIND_LENGTH) {
      return false;
    }
    for (int index = 0; index < kind.length(); index++) {
      if (!isKindCharacter(kind.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isKindCharacter(char character) {
    return (character >= 'a' && character <= 'z')
        || (character >= '0' && character <= '9')
        || character == '-'
        || character == '_';
  }
}
