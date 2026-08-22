package com.configdirector.internal.transport;

import java.io.Serial;

// Well-formed JSON that carries no configs object, so there is nothing to apply. Distinct from its
// parent because a stream may legitimately deliver such a payload, while a polling response that
// is one is a protocol violation.
public final class NotAConfigBundleException extends BundleFormatException {

  @Serial
  private static final long serialVersionUID = 1L;

  public NotAConfigBundleException(String message) {
    super(message);
  }
}
