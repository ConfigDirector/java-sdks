package com.configdirector.internal.telemetry;

enum TrackedEventWarning {
  NAME_OUTSIDE_THE_CHARACTER_LIMIT(
      "[ConfigDirectorClient] A tracked event was not reported because its name does not have 1 to "
          + TrackedEventLimits.NAME_MAXIMUM_CHARACTERS
          + " characters."),
  VALUE_NOT_A_FINITE_NUMBER(
      "[ConfigDirectorClient] A tracked event was reported without its value because the value is"
          + " not a finite number."),
  DETAILS_NOT_REPORTABLE(
      "[ConfigDirectorClient] A tracked event was reported without its details because they have"
          + " more than "
          + TrackedEventLimits.DETAILS_MAXIMUM_TOP_LEVEL_KEYS
          + " top-level keys, are larger than "
          + TrackedEventLimits.DETAILS_MAXIMUM_JSON_BYTES
          + " bytes as JSON, or cannot be written as JSON."),
  BATCH_ALREADY_FULL(
      "[ConfigDirectorClient] A tracked event was not reported because "
          + TrackedEventLimits.EVENTS_PER_BATCH
          + " tracked events are already waiting for the next telemetry batch.");

  private final String message;

  TrackedEventWarning(String message) {
    this.message = message;
  }

  String message() {
    return message;
  }
}
