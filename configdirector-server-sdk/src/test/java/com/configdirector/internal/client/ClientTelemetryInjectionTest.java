package com.configdirector.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.configdirector.ConfigEvaluation;
import com.configdirector.ConfigType;
import com.configdirector.EvaluationReason;
import com.configdirector.TelemetryOptions;
import com.configdirector.internal.SdkIdentity;
import com.configdirector.internal.telemetry.ScheduledTelemetryCollector;
import com.configdirector.internal.telemetry.TelemetryCollector;
import com.configdirector.internal.telemetry.TelemetryCollectorOptions;
import com.configdirector.internal.transport.Transport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ClientTelemetryInjectionTest {

  private final RecordingTelemetryCollector collector = new RecordingTelemetryCollector();
  private final List<TelemetryCollectorOptions> receivedOptions = new ArrayList<>();
  private DefaultConfigDirectorClient client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close(Duration.ZERO);
    }
  }

  private DefaultConfigDirectorClient clientWith(TelemetryOptions telemetry) {
    client =
        new DefaultConfigDirectorClient(
            "sdk-key",
            new SdkIdentity("some-wrapper", "1.2.3"),
            null,
            null,
            telemetry,
            LoggerFactory.getLogger(ClientTelemetryInjectionTest.class),
            (mode, options) -> new IdleTransport(),
            options -> {
              receivedOptions.add(options);
              return collector;
            });
    return client;
  }

  @Test
  void evaluations_are_recorded_through_the_collector_the_factory_returned() {
    clientWith(null);

    boolean value = client.getBoolean("flag", true);

    assertThat(value).isTrue();
    assertThat(collector.recorded).hasSize(1);
    RecordedEvaluation recorded = collector.recorded.get(0);
    assertThat(recorded.evaluation().key()).isEqualTo("flag");
    assertThat(recorded.evaluation().reason()).isEqualTo(EvaluationReason.CLIENT_NOT_READY);
    assertThat(recorded.defaultValue()).isEqualTo(true);
    assertThat(recorded.type()).isNull();
  }

  @Test
  void the_factory_receives_the_settings_the_client_was_built_with() {
    clientWith(
        TelemetryOptions.builder()
            .eventQueueLimit(123)
            .flushInterval(Duration.ofSeconds(42))
            .build());

    assertThat(receivedOptions).hasSize(1);
    TelemetryCollectorOptions options = receivedOptions.get(0);
    assertThat(options.serverSdkKey()).isEqualTo("sdk-key");
    assertThat(options.baseUrl()).isEqualTo("https://server-sdk-api.configdirector.com");
    assertThat(options.identity()).isEqualTo(new SdkIdentity("some-wrapper", "1.2.3"));
    assertThat(options.metaContext())
        .containsEntry("sdkName", "some-wrapper")
        .containsEntry("sdkVersion", "1.2.3");
    assertThat(options.http()).isNotNull();
    assertThat(options.eventQueueLimit()).isEqualTo(123);
    assertThat(options.flushInterval()).isEqualTo(Duration.ofSeconds(42));
    assertThat(options.initialFlushDelay())
        .isEqualTo(ScheduledTelemetryCollector.INITIAL_FLUSH_DELAY);
  }

  @Test
  void closing_the_client_closes_the_collector_within_the_budget() {
    clientWith(null);

    client.close(Duration.ofSeconds(1));

    assertThat(collector.closedWith).isNotNull();
    assertThat(collector.closedWith).isLessThanOrEqualTo(Duration.ofSeconds(1));
  }

  private record RecordedEvaluation(
      ConfigEvaluation evaluation, Object defaultValue, ConfigType type) {}

  private static final class RecordingTelemetryCollector implements TelemetryCollector {

    private final List<RecordedEvaluation> recorded = new ArrayList<>();
    private Duration closedWith;

    @Override
    public void recordEvaluation(
        ConfigEvaluation evaluation, Object defaultValue, ConfigType type) {
      recorded.add(new RecordedEvaluation(evaluation, defaultValue, type));
    }

    @Override
    public void close(Duration timeout) {
      closedWith = timeout;
    }

    @Override
    public void close() {
      close(Duration.ZERO);
    }
  }

  private static final class IdleTransport implements Transport {

    @Override
    public void connect(Duration timeout) {}

    @Override
    public boolean isConnected() {
      return false;
    }

    @Override
    public void close(Duration timeout) {}
  }
}
