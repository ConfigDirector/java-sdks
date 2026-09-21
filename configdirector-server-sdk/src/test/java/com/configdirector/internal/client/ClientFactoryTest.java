package com.configdirector.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ClientOptions;
import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;
import com.configdirector.ConnectionMode;
import com.configdirector.internal.SdkIdentity;
import com.configdirector.testing.TestHttpServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientFactoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final String BUNDLE = "{\"timestamp\":\"t1\",\"configs\":{}}";

  private final List<String> agents = Collections.synchronizedList(new ArrayList<>());
  private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
  private final List<String> telemetryAgents = Collections.synchronizedList(new ArrayList<>());
  private final List<String> telemetryBodies = Collections.synchronizedList(new ArrayList<>());

  private ConfigDirectorClient client;
  private TestHttpServer server;

  @BeforeEach
  void setUp() throws IOException {
    server =
        TestHttpServer.start(
            session -> {
              if (session.path().endsWith("/server/telemetry/v1")) {
                telemetryAgents.add(session.header("User-Agent"));
                telemetryBodies.add(session.bodyAsString());
                session.respond(200, "Content-Length: 0", "Connection: close");
                session.close();
                return;
              }
              agents.add(session.header("User-Agent"));
              bodies.add(session.bodyAsString());
              session.respond(
                  200,
                  "Content-Type: application/json",
                  "Content-Length: " + BUNDLE.length(),
                  "Connection: close");
              session.send(BUNDLE);
              session.close();
            });
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
    server.close();
  }

  private Consumer<ClientOptions> polling() {
    String url = server.url("/");
    return options ->
        options
            .metadata("checkout", "4.5.6")
            .connection(
                connection -> connection.mode(ConnectionMode.POLLING).url(url).timeout(TIMEOUT));
  }

  private JsonObject firstMetaContext() {
    synchronized (bodies) {
      return JsonParser.parseString(bodies.get(0)).getAsJsonObject().getAsJsonObject("metaContext");
    }
  }

  @Test
  void a_client_built_for_a_wrapper_identifies_itself_as_the_wrapper() {
    client = ClientFactory.create("sdk-key", polling(), new SdkIdentity("some-wrapper", "1.2.3"));

    client.initialize();

    assertThat(agents.get(0)).isEqualTo("some-wrapper/1.2.3");
    assertThat(firstMetaContext().get("sdkName").getAsString()).isEqualTo("some-wrapper");
    assertThat(firstMetaContext().get("sdkVersion").getAsString()).isEqualTo("1.2.3");
  }

  @Test
  void a_client_built_for_a_wrapper_reports_telemetry_as_the_wrapper() {
    client = ClientFactory.create("sdk-key", polling(), new SdkIdentity("some-wrapper", "1.2.3"));
    client.initialize();

    client.getString("greeting", "fallback");
    client.close();

    JsonObject metaContext =
        JsonParser.parseString(telemetryBodies.get(0))
            .getAsJsonObject()
            .getAsJsonObject("metaContext");
    assertThat(telemetryAgents.get(0)).isEqualTo("some-wrapper/1.2.3");
    assertThat(metaContext.get("sdkName").getAsString()).isEqualTo("some-wrapper");
    assertThat(metaContext.get("sdkVersion").getAsString()).isEqualTo("1.2.3");
  }

  @Test
  void a_client_built_for_a_wrapper_keeps_the_options_the_application_configured() {
    client = ClientFactory.create("sdk-key", polling(), new SdkIdentity("some-wrapper", "1.2.3"));

    client.initialize();

    assertThat(firstMetaContext().get("appName").getAsString()).isEqualTo("checkout");
    assertThat(firstMetaContext().get("appVersion").getAsString()).isEqualTo("4.5.6");
  }

  @Test
  void a_client_built_through_the_public_entry_point_identifies_itself_as_the_server_sdk() {
    client = ConfigDirector.client("sdk-key", polling());

    client.initialize();

    assertThat(agents.get(0)).isEqualTo(SdkIdentity.SERVER_SDK.userAgent());
    assertThat(firstMetaContext().get("sdkName").getAsString()).isEqualTo("java-server-sdk");
  }
}
