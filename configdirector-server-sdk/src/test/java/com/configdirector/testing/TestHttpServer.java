package com.configdirector.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

// A raw-socket HTTP server, because these tests need to write a response body a piece at a time,
// hold it open, and drop it mid-read -- none of which a fixed-body mock server does.
public final class TestHttpServer implements AutoCloseable {

  private final ServerSocket serverSocket;
  private final Thread acceptor;
  private final Consumer<Session> handler;
  private final List<Session> sessions = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger connections = new AtomicInteger();
  private volatile boolean running = true;

  private TestHttpServer(Consumer<Session> handler) throws IOException {
    this.handler = handler;
    this.serverSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
    this.acceptor = new Thread(this::acceptLoop, "sse-test-server");
    this.acceptor.setDaemon(true);
    this.acceptor.start();
  }

  public static TestHttpServer start(Consumer<Session> handler) throws IOException {
    return new TestHttpServer(handler);
  }

  /** Queues each connection for the test to drive by hand. */
  public static Queued startQueued() throws IOException {
    BlockingQueue<Session> queue = new LinkedBlockingQueue<>();
    return new Queued(new TestHttpServer(queue::add), queue);
  }

  public String url(String path) {
    return "http://127.0.0.1:" + serverSocket.getLocalPort() + path;
  }

  public int connectionCount() {
    return connections.get();
  }

  @Override
  public void close() {
    running = false;
    closeQuietly(serverSocket);
    synchronized (sessions) {
      sessions.forEach(Session::close);
    }
    acceptor.interrupt();
  }

  private void acceptLoop() {
    while (running) {
      try {
        Socket socket = serverSocket.accept();
        socket.setTcpNoDelay(true);
        connections.incrementAndGet();
        Thread worker = new Thread(() -> serve(socket), "sse-test-connection");
        worker.setDaemon(true);
        worker.start();
      } catch (IOException stopped) {
        return;
      }
    }
  }

  private void serve(Socket socket) {
    try {
      Session session = new Session(socket);
      sessions.add(session);
      handler.accept(session);
    } catch (IOException | RuntimeException ignored) {
      closeQuietly(socket);
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception ignored) {
      // Nothing useful to do while tearing a test fixture down.
    }
  }

  public record Queued(TestHttpServer server, BlockingQueue<Session> sessions) implements AutoCloseable {

    public Session next() throws InterruptedException {
      Session session = sessions.poll(5, TimeUnit.SECONDS);
      if (session == null) {
        throw new AssertionError("No connection arrived within 5s");
      }
      return session;
    }

    public String url(String path) {
      return server.url(path);
    }

    public int connectionCount() {
      return server.connectionCount();
    }

    @Override
    public void close() {
      server.close();
    }
  }

  public static final class Session implements AutoCloseable {

    private final Socket socket;
    private final OutputStream out;
    private final String method;
    private final String path;
    private final Map<String, String> headers = new HashMap<>();
    private final byte[] body;

    private Session(Socket socket) throws IOException {
      this.socket = socket;
      this.out = socket.getOutputStream();
      InputStream in = socket.getInputStream();

      String[] head = readHead(in).split("\r\n", -1);
      String[] requestLine = head[0].split(" ", -1);
      this.method = requestLine[0];
      this.path = requestLine.length > 1 ? requestLine[1] : "/";
      for (int i = 1; i < head.length; i++) {
        int colon = head[i].indexOf(':');
        if (colon > 0) {
          headers.put(
              head[i].substring(0, colon).toLowerCase(java.util.Locale.ROOT),
              head[i].substring(colon + 1).trim());
        }
      }

      int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
      this.body = in.readNBytes(length);
    }

    public String method() {
      return method;
    }

    public String path() {
      return path;
    }

    public String header(String name) {
      return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    public String bodyAsString() {
      return new String(body, StandardCharsets.UTF_8);
    }

    /** No Content-Length or Transfer-Encoding, so the body ends when the socket closes. */
    public void respondStreaming() {
      respond(200, "Content-Type: text/event-stream", "Cache-Control: no-store");
    }

    public void respond(int status, String... responseHeaders) {
      StringBuilder head = new StringBuilder("HTTP/1.1 " + status + " " + reason(status) + "\r\n");
      for (String header : responseHeaders) {
        head.append(header).append("\r\n");
      }
      head.append("\r\n");
      write(head.toString().getBytes(StandardCharsets.US_ASCII));
    }

    public void send(String text) {
      write(text.getBytes(StandardCharsets.UTF_8));
    }

    public void send(byte... bytes) {
      write(bytes);
    }

    private void write(byte[] bytes) {
      try {
        out.write(bytes);
        out.flush();
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
    }

    @Override
    public void close() {
      try {
        socket.close();
      } catch (IOException ignored) {
        // Already gone.
      }
    }

    private static String reason(int status) {
      return switch (status) {
        case 200 -> "OK";
        case 204 -> "No Content";
        case 302 -> "Found";
        case 401 -> "Unauthorized";
        case 500 -> "Internal Server Error";
        case 503 -> "Service Unavailable";
        default -> "Status";
      };
    }

    private static String readHead(InputStream in) throws IOException {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      int matched = 0;
      while (matched < 4) {
        int next = in.read();
        if (next < 0) {
          throw new IOException("The client closed before sending a complete request");
        }
        buffer.write(next);
        matched = next == "\r\n\r\n".charAt(matched) ? matched + 1 : (next == '\r' ? 1 : 0);
      }
      return buffer.toString(StandardCharsets.US_ASCII).trim();
    }
  }
}
