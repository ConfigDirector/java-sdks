# Spring Boot sample

A minimal [Spring Boot](https://spring.io/projects/spring-boot) app using the ConfigDirector Java
server SDK. It mirrors the `flask` sample in the Python SDK: a single `/configs` endpoint that
evaluates a handful of configs and returns them as JSON.

## Running it

```bash
./gradlew :samples:spring-boot:bootRun
```

To point it at a real ConfigDirector environment, copy the template and fill in your key:

```bash
cd samples/spring-boot
cp .env.example .env
```

`.env` is gitignored — never commit a real key.

Then:

```bash
curl 'http://localhost:3600/configs?id=user-123&plan=pro'
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

Query parameters double as the evaluation context — `id`, `name`, and `anonymous` map to the
matching `Context` fields, and anything else becomes a trait:

```
/configs?id=user-123&name=Ada&plan=pro&region=eu
```

Run the smoke tests with `./gradlew :samples:spring-boot:test`.

## The client is a singleton

This is the single most important thing the sample shows, so it lives in its own class:
[`ConfigDirectorConfiguration`](src/main/java/com/configdirector/samples/springboot/ConfigDirectorConfiguration.java).
Create one client when the application starts, share it for the whole lifetime of the process, and
close it on shutdown.

A Spring `@Bean` is exactly that — the container builds it once and injects the same instance
everywhere:

```java
@Bean(destroyMethod = "close")
public ConfigDirectorClient configDirectorClient(SampleProperties properties) {
  ConfigDirectorClient client = ConfigDirector.client(properties.getServerKey(), options -> ...);
  client.initialize();
  return client;
}
```

```java
// Every controller shares that one instance.
public ConfigsController(ConfigDirectorClient client) {
  this.client = client;
}
```

`destroyMethod = "close"` is what replaces Python's `atexit`: Spring calls it when the context
stops, which covers Ctrl-C, a container SIGTERM, and a failed startup alike.

Never build a client inside a request handler. Each one opens its own connection, blocks on
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

**Logging is yours to configure.** The sample passes its own SLF4J logger to the client, so SDK
output lands in the application's logging namespace rather than the SDK's:

```java
Logger sdkLogger = LoggerFactory.getLogger("sample.configdirector");
ConfigDirector.client(key, options -> options.logger(sdkLogger));
```

Omit `logger(...)` entirely and the SDK falls back to the SLF4J logger named `com.configdirector`.
Set `CONFIGDIRECTOR_LOG_LEVEL=DEBUG` to watch every evaluation as it happens.

**Settings come from the environment.** `application.properties` binds `configdirector.*` onto
[`SampleProperties`](src/main/java/com/configdirector/samples/springboot/SampleProperties.java), so
a real deployment supplies them as environment variables rather than editing code:

| Variable | Default | Meaning |
|---|---|---|
| `CONFIGDIRECTOR_SERVER_KEY` | `fake-sample-key` | Your server SDK key. A secret. |
| `CONFIGDIRECTOR_BASE_URL` | *(none)* | Only when routing through a proxy. |
| `CONFIGDIRECTOR_MODE` | `streaming` | `streaming`, `polling`, or `one-time`. |
| `CONFIGDIRECTOR_TIMEOUT` | `3s` | Initialization timeout. |
| `CONFIGDIRECTOR_LOG_LEVEL` | `INFO` | Set to `DEBUG` to trace evaluations. |

## Where the `.env` file comes in

Spring Boot has no `.env` format of its own, and this sample adds no library for one. A single
line in `application.properties` is enough:

```properties
spring.config.import=optional:file:.env[.properties]
```

`[.properties]` tells Spring to parse the file as `KEY=value` pairs, and `optional:` means the app
still starts when there is no `.env` at all.

**Put `.env` next to this README**, at `samples/spring-boot/.env` — `bootRun` runs with the module
directory as its working directory, and `file:.env` is resolved relative to that.

Anything in it overrides the defaults baked into `application.properties`:

```bash
CONFIGDIRECTOR_SERVER_KEY=your-real-key
CONFIGDIRECTOR_MODE=polling
CONFIGDIRECTOR_LOG_LEVEL=DEBUG
```

```
DEBUG sample.configdirector : [ConfigDirectorClient] Initializing in POLLING mode against
      https://server-sdk-api.configdirector.com with a PT3S timeout
```

Precedence is the usual Spring order, so a real environment variable still wins over the file —
which is what you want in production, where the platform injects secrets rather than shipping a
`.env`:

```bash
CONFIGDIRECTOR_MODE=one-time ./gradlew :samples:spring-boot:bootRun
```

The tests set `spring.config.import=` to empty, so a `.env` on your machine cannot change what
they assert.

## Running without a server SDK key

Without a valid key the client stays unready and every config falls back to the default this app
passes in. That is the same path a production app takes when it cannot reach ConfigDirector, so it
is worth seeing: the app keeps serving, on the defaults you chose. The smoke tests run in exactly
this state.
