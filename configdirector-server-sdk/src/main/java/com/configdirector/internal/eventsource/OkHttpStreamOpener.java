package com.configdirector.internal.eventsource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

// The only class in this package that knows OkHttp exists.
public final class OkHttpStreamOpener implements StreamOpener {

  private final OkHttpClient client;

  public OkHttpStreamOpener() {
    this(new OkHttpClient.Builder().build());
  }

  public OkHttpStreamOpener(OkHttpClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public ResponseStream open(StreamRequest request) {
    Request.Builder builder = new Request.Builder().url(request.url());
    request.headers().forEach(builder::header);
    byte[] body = request.body();
    builder.method(request.method(), body == null ? null : RequestBody.create(body));

    Call call = clientFor(request).newCall(builder.build());
    Response response;
    try {
      response = call.execute();
    } catch (IOException error) {
      throw new StreamConnectException("Could not open the event stream: " + error, error);
    }
    return new OkHttpResponseStream(call, response);
  }

  @Override
  public void close() {
    client.connectionPool().evictAll();
  }

  private OkHttpClient clientFor(StreamRequest request) {
    // newBuilder shares the connection pool and dispatcher rather than duplicating them.
    return client
        .newBuilder()
        .connectTimeout(request.connectTimeout())
        .readTimeout(request.readTimeout())
        // A whole-call timeout would kill a healthy long-lived stream on schedule. Silence is what
        // makes a stream dead, and readTimeout is what measures that.
        .callTimeout(Duration.ZERO)
        // Reconnection and its backoff belong to EventSourceClient. OkHttp must not retry
        // underneath it, or one logical attempt would become several.
        .retryOnConnectionFailure(false)
        .followRedirects(request.followRedirects())
        .followSslRedirects(request.followRedirects())
        .build();
  }

  private static final class OkHttpResponseStream implements ResponseStream {

    private final Call call;
    private final Response response;
    private final Reader reader;

    OkHttpResponseStream(Call call, Response response) {
      this.call = call;
      this.response = response;
      // Explicitly UTF-8 rather than charStream(), which reads a Content-Type charset the server
      // may well not have sent.
      this.reader = new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8);
    }

    @Override
    public int status() {
      return response.code();
    }

    @Override
    public int read(char[] destination) {
      try {
        return reader.read(destination);
      } catch (SocketTimeoutException stalled) {
        throw new StreamStalledException("The event stream went silent: " + stalled, stalled);
      } catch (IOException closed) {
        // The peer went away, or cancel() pulled the socket out from under this read.
        throw new StreamClosedException("The event stream ended: " + closed, closed);
      }
    }

    @Override
    public void cancel() {
      call.cancel();
    }

    @Override
    public void close() {
      response.close();
    }
  }
}
