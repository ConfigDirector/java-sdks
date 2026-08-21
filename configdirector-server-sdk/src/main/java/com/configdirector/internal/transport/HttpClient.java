package com.configdirector.internal.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

// Owns the connection pool behind one client's request/response calls. Per client rather than per
// process: connections a client opened have to be released when it closes, and two clients must
// not share a pool either can close underneath the other. Kept separate from the streaming pool
// because their lifetimes differ -- a stream holds its connection open, these cycle per request.
public final class HttpClient implements AutoCloseable {

  // Caps how much of a response is held in memory. A config bundle is orders of magnitude smaller;
  // anything larger is a misconfigured proxy or a hostile endpoint, and a server SDK must not let
  // either exhaust the host's memory.
  public static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

  private static final MediaType JSON = MediaType.get("application/json");

  private final OkHttpClient client;

  public HttpClient() {
    this(new OkHttpClient.Builder().build());
  }

  public HttpClient(OkHttpClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  public HttpResponse post(String url, byte[] body, Map<String, String> headers, Duration timeout) {
    Request.Builder builder;
    try {
      builder = new Request.Builder().url(url);
    } catch (IllegalArgumentException unusable) {
      // The URL itself is wrong, so every retry would fail identically.
      throw new UnusableUrlException("The URL '" + url + "' is not usable: " + unusable, unusable);
    }
    headers.forEach(builder::header);
    builder.post(RequestBody.create(body, JSON));

    Call call =
        client
            .newBuilder()
            .connectTimeout(timeout)
            .readTimeout(timeout)
            // Polling cadence is the transport's job. OkHttp must not retry underneath it, or one
            // logical poll would become several.
            .retryOnConnectionFailure(false)
            .build()
            .newCall(builder.build());

    // An error response is still a response: the caller reads the status and body to decide
    // whether the failure is worth retrying.
    try (Response response = call.execute()) {
      return new HttpResponse(response.code(), readBody(response.body()));
    } catch (IOException error) {
      // Refused, unresolved, timed out. All worth retrying.
      throw new ConfigDirectorConnectionException("Connection failed with error: " + error, null, error);
    }
  }

  // Releases pooled connections. A request already in flight is its caller's to finish.
  @Override
  public void close() {
    client.connectionPool().evictAll();
  }

  private static String readBody(ResponseBody body) throws IOException {
    StringBuilder text = new StringBuilder();
    char[] buffer = new char[8192];
    try (InputStream bytes = body.byteStream();
        Reader reader = new InputStreamReader(bytes, StandardCharsets.UTF_8)) {
      int count;
      while ((count = reader.read(buffer)) >= 0) {
        if (text.length() + count > MAX_RESPONSE_BYTES) {
          throw new ConfigDirectorConnectionException(
              "The server response exceeded the " + MAX_RESPONSE_BYTES + " byte limit and was discarded");
        }
        text.append(buffer, 0, count);
      }
    }
    return text.toString();
  }
}
