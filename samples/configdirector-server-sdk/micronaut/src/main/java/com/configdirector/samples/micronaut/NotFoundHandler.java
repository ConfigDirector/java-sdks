package com.configdirector.samples.micronaut;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import java.util.Map;

@Controller
public class NotFoundHandler {

  /** {@code global} makes this the 404 for every route, not just the ones on this controller. */
  @Error(status = HttpStatus.NOT_FOUND, global = true)
  public HttpResponse<Map<String, String>> notFound() {
    return HttpResponse.notFound(Map.of("error", "Not found. Try GET /configs"));
  }
}
