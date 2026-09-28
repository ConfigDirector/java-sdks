package com.configdirector.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.configdirector.ConnectionMode;
import com.configdirector.ConnectionOptions;
import com.configdirector.internal.SdkIdentity;
import com.configdirector.internal.transport.Transport;
import com.configdirector.internal.transport.TransportOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PollingIntervalResolutionTest {

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> appender;
  private final List<TransportOptions> transportOptions = new ArrayList<>();
  private DefaultConfigDirectorClient client;

  @BeforeEach
  void attachAppender() {
    logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("polling-interval-under-test");
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    if (client != null) {
      client.close(Duration.ZERO);
    }
  }

  private DefaultConfigDirectorClient clientWith(ConnectionOptions connection) {
    client =
        new DefaultConfigDirectorClient(
            "sdk-key",
            new SdkIdentity("some-wrapper", "1.2.3"),
            null,
            connection,
            null,
            logger,
            (mode, options) -> {
              transportOptions.add(options);
              return new IdleTransport();
            });
    return client;
  }

  private static ConnectionOptions polling(Duration interval) {
    return ConnectionOptions.builder()
        .mode(ConnectionMode.POLLING)
        .pollingInterval(interval)
        .build();
  }

  private Duration intervalHandedToTheTransport() {
    assertThat(transportOptions).hasSize(1);
    return transportOptions.get(0).pollingInterval();
  }

  private List<ILoggingEvent> warnings() {
    return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
  }

  @Test
  void a_polling_client_without_an_interval_polls_every_five_minutes() {
    clientWith(ConnectionOptions.builder().mode(ConnectionMode.POLLING).build());

    assertThat(intervalHandedToTheTransport()).isEqualTo(Duration.ofMinutes(5));
    assertThat(warnings()).isEmpty();
  }

  @Test
  void an_interval_below_the_minimum_is_raised_to_the_minimum_with_one_warning() {
    clientWith(polling(Duration.ofSeconds(10)));

    assertThat(intervalHandedToTheTransport()).isEqualTo(Duration.ofSeconds(60));
    assertThat(warnings()).hasSize(1);
    assertThat(warnings().get(0).getFormattedMessage())
        .contains("[ConfigDirectorClient]")
        .contains("pollingInterval of PT10S is below the minimum of PT1M. Using PT1M.");
  }

  @Test
  void exactly_the_minimum_is_accepted_unchanged() {
    clientWith(polling(Duration.ofSeconds(60)));

    assertThat(intervalHandedToTheTransport()).isEqualTo(Duration.ofSeconds(60));
    assertThat(warnings()).isEmpty();
  }

  @Test
  void an_interval_above_the_minimum_is_accepted_unchanged() {
    clientWith(polling(Duration.ofMinutes(2)));

    assertThat(intervalHandedToTheTransport()).isEqualTo(Duration.ofMinutes(2));
    assertThat(warnings()).isEmpty();
  }

  @Test
  void a_zero_interval_does_not_throw_and_is_raised_to_the_minimum() {
    assertThatNoException().isThrownBy(() -> clientWith(polling(Duration.ZERO)));

    assertThat(intervalHandedToTheTransport()).isEqualTo(Duration.ofSeconds(60));
    assertThat(warnings()).hasSize(1);
    assertThat(warnings().get(0).getFormattedMessage()).contains("below the minimum");
  }

  @Test
  void a_negative_interval_does_not_throw_and_is_raised_to_the_minimum() {
    assertThatNoException().isThrownBy(() -> clientWith(polling(Duration.ofSeconds(-30))));

    assertThat(intervalHandedToTheTransport()).isEqualTo(Duration.ofSeconds(60));
    assertThat(warnings()).hasSize(1);
    assertThat(warnings().get(0).getFormattedMessage()).contains("below the minimum");
  }

  @Test
  void in_streaming_mode_a_low_interval_neither_throws_nor_logs() {
    assertThatNoException()
        .isThrownBy(
            () ->
                clientWith(
                    ConnectionOptions.builder()
                        .mode(ConnectionMode.STREAMING)
                        .pollingInterval(Duration.ofSeconds(10))
                        .build()));

    assertThat(intervalHandedToTheTransport()).isEqualTo(Duration.ofSeconds(10));
    assertThat(appender.list).isEmpty();
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
