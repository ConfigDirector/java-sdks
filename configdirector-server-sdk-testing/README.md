# ConfigDirector Java Server SDK Testing

Testing tools for the [ConfigDirector Java server SDK](../configdirector-server-sdk/), published to
the ConfigDirector Maven repository as `com.configdirector:server-sdk-testing`. They let you test the code
that reads your configs and flags without a network connection and without changing production
code.

A **test client** is the SDK's real client connected to an in-memory server that your test
controls. Only the connection to ConfigDirector is replaced. Everything else is the same code that
runs in production, so the code under test behaves exactly as it does against ConfigDirector, and
no network connection is opened and no telemetry is sent.

## Installation

The artifact is published to the ConfigDirector Maven repository, `https://maven.configdirector.com`,
like the SDK. Add the repository to your build once, next to `mavenCentral()`, then add the
dependency in test scope. If your build declares its repositories in `settings.gradle` or
`settings.gradle.kts` under `dependencyResolutionManagement`, the repository block goes there
instead. The repository, the signing key and how to verify what it serves are described at
https://docs.configdirector.com/sdks/maven-repository.

The artifact is released together with the SDK and must be the same version as the
`com.configdirector:server-sdk` on your test classpath; creating a test client fails with a message
naming both versions otherwise.

Gradle, Kotlin DSL:

```kotlin
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = "ConfigDirector"
                url = uri("https://maven.configdirector.com")
            }
        }
        filter {
            includeGroup("com.configdirector")
        }
    }
}

dependencies {
    testImplementation("com.configdirector:server-sdk-testing:1.8.0")
}
```

Gradle, Groovy DSL:

```groovy
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = 'ConfigDirector'
                url = 'https://maven.configdirector.com'
            }
        }
        filter {
            includeGroup 'com.configdirector'
        }
    }
}

dependencies {
    testImplementation 'com.configdirector:server-sdk-testing:1.8.0'
}
```

Maven:

```xml
<repositories>
  <repository>
    <id>configdirector</id>
    <url>https://maven.configdirector.com</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>

<dependency>
  <groupId>com.configdirector</groupId>
  <artifactId>server-sdk-testing</artifactId>
  <version>1.8.0</version>
  <scope>test</scope>
</dependency>
```

## Test your code

```java
import com.configdirector.testing.ConfigDirectorTesting;
import com.configdirector.testing.TestClient;

try (TestClient testClient = ConfigDirectorTesting.createTestClient(Map.of("new-checkout", true))) {
  CheckoutService service = new CheckoutService(testClient.client());
  testClient.client().initialize();

  assertThat(service.isNewCheckoutEnabled("user-123")).isTrue();

  testClient.setValue("new-checkout", false);
  assertThat(service.isNewCheckoutEnabled("user-123")).isFalse();
}
```

`testClient.client()` is a `ConfigDirectorClient`, so it goes anywhere your code accepts one. It
starts uninitialized, like a production client, because the code under test usually owns the call
to `initialize`; with no hold or failure armed, `initialize` completes at once with the stored
values. `TestClient` is `AutoCloseable` and closes its client.

### With JUnit Jupiter

`ConfigDirectorTestExtension` creates one test client per test, seeds it from annotations on the
test class and the test method, injects it, and closes it after the test. Annotations on the
class apply to every test; annotations on a method add to or override them.

```java
@ExtendWith(ConfigDirectorTestExtension.class)
class CheckoutTest {

  @Test
  @BooleanConfigValue(key = "new-checkout", value = true)
  @IntegerConfigValue(key = "max-items", value = 20)
  @JsonConfigValue(key = "theme", value = "{\"color\": \"blue\"}")
  void showsTheNewCheckout(TestClient testClient) {
    testClient.client().initialize();
    ...
  }
}
```

Each config type has its own annotation, because annotation attributes are compile-time constants:
`@BooleanConfigValue`, `@IntegerConfigValue` (a `long`), `@FloatConfigValue` (a `double`),
`@StringConfigValue`, and `@JsonConfigValue`, whose `value` is strict JSON text holding an object or
an array. A `ConfigDirectorClient` parameter resolves to the same client as a `TestClient`
parameter. The extension works with JUnit 5.10 or newer and JUnit 6.

The extension is for code that receives the client from the test. A Spring, Micronaut, or Quarkus
context that outlives a test keeps one test client in a static field, replaces the application's
client bean with `testClient.client()`, initializes it where the application's own bean method
would have, and calls `replaceValues` in each test's setup. Replacing matters: a second bean leaves
the application's own client in place, which connects to ConfigDirector on startup.

### Values

`createTestClient`, `setValue`, and `replaceValues` take native values, and the config type follows
from each value: a `Boolean`, an `Integer` or `Long` (an integer config), a `Float` or `Double` (a
float config), a `String`, or a `Map` with `String` keys or a `List` (a JSON config). `setJsonValue`
takes strict JSON text holding an object or an array; a `String` given to `setValue` is always a
string config. Every value is served as an unconditional config, so every context receives the same
value; a test that needs different values per context is written as one test per value. A `null`
value, a blank key, a non-finite number, another `Number` subclass such as `BigDecimal`, a `Map`
with non-`String` keys, or JSON contents that cannot be encoded throw
`ConfigDirectorValidationException`, and nothing changes.

Reads behave as they do in production: `setValue("k", true)` read as a string returns the in-code
default value with the `TYPE_MISMATCH` reason, and `setValue("k", "")` serves the in-code default
value with the `VALUE_MISSING` reason. Numbers inside JSON configs come back as `Long` and `Double`,
so `Map.of("n", 1)` reads back as `{n=1L}`.

### Controls

| Control | Effect |
| --- | --- |
| `setValue(key, value)` | Stores the value and, once the client is connected, delivers an update carrying only `key`. Watches of `key`, `configsUpdated` handlers, and reads see it before the call returns. |
| `setJsonValue(key, json)` | As `setValue`, for strict JSON text that becomes a JSON config. |
| `removeValue(key)` | Removes the value and delivers a full update without it. Reads return the in-code default value with the `CONFIG_STATE_MISSING` reason, watches of `key` receive the default, and `configsUpdated` lists `key` in `removedKeys()`. |
| `replaceValues(values)` | Replaces every stored value, disarms any armed hold or failure, and delivers a full update. Use it to reset a test client shared across tests. |
| `holdInitialization()` | The next `initialize` waits until `completeInitialization()` or `failInitialization()`, or until the client's timeout elapses. `initialize` blocks, so a test calls it on another thread. |
| `completeInitialization()` | Delivers the stored values to the held `initialize`, on the calling thread, so the client is ready when the call returns. Called while a hold is armed but not picked up, it disarms the hold. |
| `failInitialization()` | Fails the held `initialize` the way an invalid SDK key does: it completes promptly, the client is not ready, and the error is logged. Called with no `initialize` held, it arms the next one to fail. |

After a failed attempt, the next `initialize` succeeds and delivers the values stored in the
meantime.

### Options

`createTestClient(values, options -> ...)` takes `timeout` (the client's connection timeout, which
bounds how long a held `initialize` waits; defaults to the SDK's production timeout) and `logger`
(defaults to the SDK's logger, `com.configdirector`).

### What to expect

- The first update is delivered while `initialize` connects, so watches registered before
  `initialize` run before it returns, and `configsUpdated` fires before `clientReady`.
- Watches and handlers run on the thread that calls `setValue`, `removeValue`,
  `replaceValues`, `completeInitialization`, or `initialize`, not on a transport thread.
- A held `initialize` that times out, or that is interrupted by `close`, leaves the client not
  ready until the next `initialize`, and logs the SDK's timeout warning; production streaming
  would keep retrying in the background.
- Watches fire on every update carrying their key, whether or not the value changed, as in
  production. `removeValue` and `replaceValues` deliver a full update, so they also fire the
  watches of every remaining key.
- A `RuntimeException` thrown by a watch or handler is logged and does not propagate, as with the
  production transports. An `Error`, such as a failed assertion's `AssertionError`, propagates out
  of the call that delivered the update.
- After `close`, reads return the in-code default value with the `CLIENT_NOT_READY` reason,
  `getAllConfigs` returns an empty map, `initialize` throws `ConfigDirectorValidationException`,
  the test client's controls are silent no-ops, and no SDK thread is left running.

## Documentation

Full details are in the [official documentation](https://docs.configdirector.com/sdks/server/java).
