package com.configdirector.samples.openfeature.micronaut;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import java.util.Map;

@Controller
public class NotFoundHandler {

  @Error(status = HttpStatus.NOT_FOUND, global = true)
  public HttpResponse<Map<String, String>> notFound() {
    return HttpResponse.notFound(Map.of("error", "Not found. Try GET /configs"));
  }
}
