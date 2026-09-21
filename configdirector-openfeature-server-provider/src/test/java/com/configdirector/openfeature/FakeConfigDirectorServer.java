package com.configdirector.openfeature;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class FakeConfigDirectorServer implements AutoCloseable {

  private static final String STREAM_PATH = "/server/sse/v1";
  private static final String TELEMETRY_PATH = "/server/telemetry/v1";

  private final HttpServer server;
  private final ExecutorService workers =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "fake-configdirector-server");
            thread.setDaemon(true);
            return thread;
          });
  private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
  private final BlockingQueue<Request> streamRequests = new LinkedBlockingQueue<>();
  private final BlockingQueue<Request> telemetryRequests = new LinkedBlockingQueue<>();

  record Request(String userAgent, String body) {}

  private FakeConfigDirectorServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(workers);
    server.createContext(STREAM_PATH, this::stream);
    server.createContext(TELEMETRY_PATH, this::telemetry);
    server.createContext("/", FakeConfigDirectorServer::acknowledge);
    server.start();
  }

  static FakeConfigDirectorServer start() {
    try {
      return new FakeConfigDirectorServer();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  void send(String bundle) {
    outbox.add(bundle);
  }

  Request nextStreamRequest() throws InterruptedException {
    return next(streamRequests, "stream");
  }

  Request nextTelemetryRequest() throws InterruptedException {
    return next(telemetryRequests, "telemetry");
  }

  private static Request next(BlockingQueue<Request> requests, String kind)
      throws InterruptedException {
    Request request = requests.poll(5, TimeUnit.SECONDS);
    if (request == null) {
      throw new AssertionError("No " + kind + " request arrived within 5s");
    }
    return request;
  }

  @Override
  public void close() {
    server.stop(0);
    workers.shutdownNow();
  }

  private void stream(HttpExchange exchange) throws IOException {
    streamRequests.add(received(exchange));
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream body = exchange.getResponseBody()) {
      while (true) {
        String bundle = outbox.take();
        body.write(("data: " + bundle + "\n\n").getBytes(StandardCharsets.UTF_8));
        body.flush();
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

  private void telemetry(HttpExchange exchange) throws IOException {
    telemetryRequests.add(received(exchange));
    respondEmpty(exchange);
  }

  private static void acknowledge(HttpExchange exchange) throws IOException {
    read(exchange);
    respondEmpty(exchange);
  }

  private static Request received(HttpExchange exchange) throws IOException {
    return new Request(exchange.getRequestHeaders().getFirst("User-Agent"), read(exchange));
  }

  private static String read(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static void respondEmpty(HttpExchange exchange) throws IOException {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
