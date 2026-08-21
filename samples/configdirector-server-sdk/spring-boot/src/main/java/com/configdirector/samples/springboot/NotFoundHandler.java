package com.configdirector.samples.springboot;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class NotFoundHandler {

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Map<String, String>> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("error", "Not found. Try GET /configs"));
  }
}
