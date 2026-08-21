package com.configdirector;

/** Why an evaluation produced the value that it did. */
public enum EvaluationReason {
  FOUND_MATCH,
  CONFIG_STATE_MISSING,
  CLIENT_NOT_READY,
  VALUE_MISSING,
  INVALID_NUMBER,
  INVALID_JSON,
  INVALID_BOOLEAN
}
