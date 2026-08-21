package com.configdirector.internal.transport;

public record HttpResponse(int status, String body) {

  public boolean ok() {
    return status >= 200 && status < 300;
  }
}
