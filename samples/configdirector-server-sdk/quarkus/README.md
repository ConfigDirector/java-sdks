# Quarkus sample

A minimal [Quarkus](https://quarkus.io) app using the ConfigDirector Java server SDK. It is the
same app as the [`spring-boot`](../spring-boot/) and [`micronaut`](../micronaut/) samples — a
single `/configs` endpoint that evaluates a handful of configs and returns them as JSON — written
the Quarkus way, so the three can be read side by side.

## Running it

```bash
./gradlew :samples:configdirector-server-sdk:quarkus:quarkusRun
```

To point it at a real ConfigDirector environment, copy the template and fill in your key:

```bash
cd samples/configdirector-server-sdk/quarkus
cp .env.example .env
```

`.env` is gitignored — never commit a real key.

Then:

```bash
curl 'http://localhost:3602/configs?id=user-123&plan=pro'
```

```json
{
  "temporary-feature-flag": true,
  "permanent-kill-switch": false,
  "integer-config": 10,
  "day-of-the-week-config": "Friday",
  "json-value-config": {}
}
```

Port 3602, so this, the Spring Boot sample on 3600 and the Micronaut sample on 3601 can run at the
same time.

Query parameters double as the evaluation context — `id`, `name`, and `anonymous` map to the
matching `Context` fields, and anything else becomes a trait:

```
/configs?id=user-123&name=Ada&plan=pro&region=eu
```

Run the smoke tests with `./gradlew :samples:configdirector-server-sdk:quarkus:test`.

### Live coding

`quarkusDev` is the mode Quarkus is known for — edit a source file, hit the endpoint again, and
the change is already there:

```bash
./gradlew :samples:configdirector-server-sdk:quarkus:quarkusDev
```

Worth knowing what that does to the client: a live reload restarts the application context, which
disposes the old client and produces a new one, so a reload costs another `initialize()`. That is
a development convenience, not a reason to build clients per request — see below.

## Which SDK it builds against

By default the sample depends on the released artifact, exactly as your own app would:

```groovy
implementation 'com.configdirector:configdirector-server-sdk:1.0.0'
```

Pass `-PuseLocalSdk` to build it against
[`configdirector-server-sdk/`](../../../configdirector-server-sdk/) in this repository instead:

```bash
./gradlew :samples:configdirector-server-sdk:quarkus:quarkusRun -PuseLocalSdk
```

That is how to try an unreleased SDK change against a real consumer, and CI sets it on every job
so a breaking API change fails here before it ships.

Note the `enforcedPlatform` next to it. Quarkus augments the whole application at build time out
of the extension jars on the classpath, so the extensions and the Gradle plugin have to be on the
same Quarkus release; the BOM is what pins them, and `enforcedPlatform` is what stops a transitive
dependency from quietly downgrading one of them.

## It compiles on Java 17

Unlike the [`micronaut`](../micronaut/) sample, this one holds the SDK's floor:

```groovy
options.release = 17
```

Quarkus 3 still supports Java 17, so the sample can be compiled against the real 17 API — which
means it cannot accidentally demonstrate an API a consumer on 17 could not call. The toolchain is
21, matching the rest of this build; only the bytecode target is 17.

## The client is a singleton

This is the single most important thing the sample shows, so it lives in its own class:
[`ConfigDirectorProducer`](src/main/java/com/configdirector/samples/quarkus/ConfigDirectorProducer.java).
Create one client when the application starts, share it for the whole lifetime of the process, and
close it on shutdown.

A CDI producer method is exactly that — the container calls it once and injects the same instance
everywhere:

```java
@Produces
@Singleton
@Startup
public ConfigDirectorClient configDirectorClient(SampleConfig config) {
  ConfigDirectorClient client = ConfigDirector.client(config.serverKey(), options -> ...);
  client.initialize();
  return client;
}

public void close(@Disposes ConfigDirectorClient client) {
  client.close();
}
```

```java
// Every resource shares that one instance.
public ConfigsResource(ConfigDirectorClient client) {
  this.client = client;
}
```

The `@Disposes` method is what replaces Python's `atexit`: CDI calls it when the container stops,
which covers Ctrl-C, a container SIGTERM, and a failed startup alike. It is the counterpart to
Spring's `destroyMethod` and Micronaut's `preDestroy`.

`@Startup` is the other half. Without it the bean is created on first injection, which here would
be the first request that needs a config — that request would pay for `initialize()`, and every
request before the client existed would have been served defaults by a client that had not been
asked to connect yet. `@Startup` moves that cost to application startup, where it belongs.

`@Singleton` rather than `@ApplicationScoped` is deliberate too. An `@ApplicationScoped` bean is
normal-scoped, so injection points receive a CDI client proxy that forwards every call to the real
instance. Nothing here needs that indirection, and `@Singleton` hands out the client itself.

Never build a client inside a resource method. Each one opens its own connection, blocks on
`initialize()`, and starts out not-ready — so it would serve defaults more or less forever.

Concurrency is not a reason to make more of them: the client is thread safe, so every request
thread shares this one safely.

Evaluation itself is cheap. `getBoolean()` and friends read config state the client already holds
in memory, with no network call on the request path, which is what makes it safe to call several
times per request.

## What else it demonstrates

**Initialization is explicit and non-fatal.** `initialize()` blocks until the initial config state
arrives or the timeout elapses, and never throws on connection failure. The sample checks
`isReady()`, logs a warning, and carries on serving defaults.

**Defaults are the fallback.** Every getter takes the value to serve when ConfigDirector is
unreachable, so it should be the safe choice. Which getter you call also decides how the config
value is parsed — `getInteger` on a value of `"not-a-number"` returns your default rather than
throwing.

**Context is per-request; the client is not.** `contextFrom()` maps query parameters onto a
`Context`; a real app would build this from the authenticated session.

There is a name collision worth knowing about here: `com.configdirector.Context` and
`jakarta.ws.rs.core.Context` share a simple name, so a resource that imports the first cannot also
import the second. Quarkus REST injects `UriInfo` without the annotation JAX-RS would normally
require, which sidesteps it:

```java
@GET
public Map<String, Object> configs(UriInfo uriInfo) {
```

**Logging is yours to configure.** The sample passes its own SLF4J logger to the client, so SDK
output lands in the application's logging namespace rather than the SDK's:

```java
Logger sdkLogger = LoggerFactory.getLogger("sample.configdirector");
ConfigDirector.client(key, options -> options.logger(sdkLogger));
```

Quarkus routes SLF4J into JBoss Log Manager on its own, so the SDK needs no bridge and no
`logback.xml`. Omit `logger(...)` entirely and the SDK falls back to the SLF4J logger named
`com.configdirector`. Set `CONFIGDIRECTOR_LOG_LEVEL=DEBUG` to watch every evaluation as it
happens.

Unlike the Micronaut sample, the SDK's own startup logging is at the level you asked for: Quarkus
configures logging before it builds any bean, so `DEBUG` covers `initialize()` too.

**Settings come from the environment.** `configdirector.*` binds onto
[`SampleConfig`](src/main/java/com/configdirector/samples/quarkus/SampleConfig.java), so a real
deployment supplies them as environment variables rather than editing code:

| Variable                    | Default           | Meaning                                |
| --------------------------- | ----------------- | -------------------------------------- |
| `CONFIGDIRECTOR_SERVER_KEY` | `fake-sample-key` | Your server SDK key. A secret.         |
| `CONFIGDIRECTOR_BASE_URL`   | _(none)_          | Only when routing through a proxy.     |
| `CONFIGDIRECTOR_MODE`       | `streaming`       | `streaming`, `polling`, or `one-time`. |
| `CONFIGDIRECTOR_TIMEOUT`    | `3s`              | Initialization timeout.                |
| `CONFIGDIRECTOR_LOG_LEVEL`  | `INFO`            | Set to `DEBUG` to trace evaluations.   |

`SampleConfig` is an interface, not a mutable bean — Quarkus generates the implementation at build
time and hands out an immutable instance, so there are no setters for anything to call after
startup:

```java
@ConfigMapping(prefix = "configdirector")
public interface SampleConfig {

  @WithDefault("fake-sample-key")
  String serverKey();

  Optional<String> baseUrl();
}
```

One consequence to know about: every key under the prefix has to appear on the interface. Quarkus
validates a mapped prefix and fails startup on a property it cannot place, which is why
`logLevel()` is declared even though only `application.properties` reads it.

Note what `application.properties` does _not_ contain: any `${CONFIGDIRECTOR_SERVER_KEY}`
placeholder. MicroProfile Config already understands the `SCREAMING_SNAKE_CASE` spelling of a
property name, so `CONFIGDIRECTOR_SERVER_KEY` overrides `configdirector.server-key` on its own and
the file only has to state the default:

```properties
configdirector.server-key=fake-sample-key
```

The one exception is `quarkus.log.category."sample.configdirector".level`, whose name is not
derived from the variable, so that line does spell the expression out.

## Where the `.env` file comes in

Quarkus reads `.env` itself. There is no library to add and no property source to write — unlike
the Micronaut sample, which needs a small class for this — and the file's keys are spelled the
same way the environment's are, so `CONFIGDIRECTOR_SERVER_KEY=...` resolves
`configdirector.server-key` exactly as the exported variable would.

**Put `.env` next to this README**, at `samples/configdirector-server-sdk/quarkus/.env`. Quarkus
resolves it relative to the working directory, so `build.gradle` points `quarkusRun` and
`quarkusDev` at this module rather than at the repository root Gradle would use by default:

```groovy
tasks.named('quarkusRun') {
    workingDirectory = projectDir
}
```

An absent file is not an error; the app starts on the defaults in `application.properties`.
Anything in it overrides those defaults:

```bash
CONFIGDIRECTOR_SERVER_KEY=your-real-key
CONFIGDIRECTOR_MODE=polling
CONFIGDIRECTOR_LOG_LEVEL=DEBUG
```

```
INFO  [com.configdirector.samples.quarkus.ConfigDirectorProducer] (main)
      ConfigDirector client created once at startup (mode=POLLING, ready=false)
DEBUG [sample.configdirector] (executor-thread-1) [ConfigDirectorClient] No config state found
      for temporary-feature-flag, returning the default value
```

A real environment variable still wins over the file — which is what you want in production, where
the platform injects secrets rather than shipping a `.env`:

```bash
CONFIGDIRECTOR_MODE=one-time ./gradlew :samples:configdirector-server-sdk:quarkus:quarkusRun
```

Because `.env` is read from the working directory, a developer's local file would otherwise reach
the test suite too. The `test` task runs from a directory that has none:

```groovy
tasks.named('test') {
    workingDir = layout.buildDirectory.dir('test-working-dir').get().asFile
}
```

The tests pin the rest through a
[`QuarkusTestProfile`](src/test/java/com/configdirector/samples/quarkus/UnreachableConfigDirectorProfile.java),
which overrides `configdirector.*` for the suite.

## Running without a server SDK key

Without a valid key the client stays unready and every config falls back to the default this app
passes in. That is the same path a production app takes when it cannot reach ConfigDirector, so it
is worth seeing: the app keeps serving, on the defaults you chose. The smoke tests run in exactly
this state.
