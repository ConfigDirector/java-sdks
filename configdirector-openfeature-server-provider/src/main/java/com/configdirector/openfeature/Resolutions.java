package com.configdirector.openfeature;

import com.configdirector.ConfigEvaluation;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Reason;

final class Resolutions {

  private Resolutions() {}

  static <T> ProviderEvaluation<T> of(T value, ConfigEvaluation evaluation) {
    if (evaluation == null) {
      return ProviderEvaluation.<T>builder().value(value).build();
    }

    return switch (evaluation.reason()) {
      case FOUND_MATCH ->
          ProviderEvaluation.<T>builder()
              .value(value)
              .variant(evaluation.valueId())
              .reason(Reason.TARGETING_MATCH.toString())
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

  private static <T> ProviderEvaluation<T> error(T value, ErrorCode errorCode, String message) {
    return ProviderEvaluation.<T>builder()
        .value(value)
        .reason(Reason.ERROR.toString())
        .errorCode(errorCode)
        .errorMessage(message)
        .build();
  }
}
