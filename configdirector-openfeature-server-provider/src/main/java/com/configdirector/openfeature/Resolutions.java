package com.configdirector.openfeature;

import com.configdirector.ConfigEvaluation;
import com.configdirector.Variation;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;

final class Resolutions {

  private Resolutions() {}

  static <T> ProviderEvaluation<T> of(T value, ConfigEvaluation evaluation) {
    return switch (evaluation.reason()) {
      case FOUND_MATCH ->
          ProviderEvaluation.<T>builder()
              .value(value)
              .variant(variantOf(evaluation))
              .reason(Reason.TARGETING_MATCH.toString())
              .build();
      case ROLLOUT_SKIPPED_NO_IDENTIFIER ->
          ProviderEvaluation.<T>builder()
              .value(value)
              .variant(variantOf(evaluation))
              .reason(Reason.DEFAULT.toString())
              .build();
      case VALUE_MISSING ->
          ProviderEvaluation.<T>builder().value(value).reason(Reason.DEFAULT.toString()).build();
      case CONFIG_STATE_MISSING ->
          error(
              value,
              ErrorCode.FLAG_NOT_FOUND,
              "No config with the key '" + evaluation.key() + "' was found.");
      case CLIENT_NOT_READY ->
          error(
              value,
              ErrorCode.PROVIDER_NOT_READY,
              "The ConfigDirector client has not received any configs yet.");
      default ->
          evaluation.isDefault()
              ? error(
                  value,
                  ErrorCode.TYPE_MISMATCH,
                  "The value of '"
                      + evaluation.key()
                      + "' does not match the requested type ("
                      + evaluation.reason().wireName()
                      + ").")
              : ProviderEvaluation.<T>builder().value(value).build();
    };
  }

  private static String variantOf(ConfigEvaluation evaluation) {
    Variation variation = evaluation.variation();
    return variation == null || variation.name() == null ? evaluation.valueId() : variation.name();
  }

  private static <T> ProviderEvaluation<T> error(T value, ErrorCode errorCode, String message) {
    return ProviderEvaluation.<T>builder()
        .value(value)
        .reason(Reason.ERROR.toString())
        .errorCode(errorCode)
        .errorMessage(message)
        .build();
  }
}
