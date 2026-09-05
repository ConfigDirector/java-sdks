# Micronaut sample

A minimal [Micronaut](https://micronaut.io) app using the ConfigDirector Java server SDK. It is
the same app as the [`spring-boot`](../spring-boot/) and [`quarkus`](../quarkus/) samples — a
single `/configs` endpoint that evaluates a handful of configs and returns them as JSON — written
the Micronaut way, so the three can be read side by side.

## Running it

```bash
./gradlew :samples:configdirector-server-sdk:micronaut:run
```

To point it at a real ConfigDirector environment, copy the template and fill in your key:

```bash
cd samples/configdirector-server-sdk/micronaut
cp .env.example .env
```

`.env` is gitignored — never commit a real key.

Then:

```bash
curl 'http://localhost:3601/configs?id=user-123&plan=pro'
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

Port 3601, so this, the Spring Boot sample on 3600 and the Quarkus sample on 3602 can run at
the same time.

Query parameters double as the evaluation context — `id`, `name`, and `anonymous` map to the
matching `Context` fields, and anything else becomes a trait:

```
/configs?id=user-123&name=Ada&plan=pro&region=eu
```

Run the smoke tests with `./gradlew :samples:configdirector-server-sdk:micronaut:test`.

## Which SDK it builds against

By default the sample depends on the released artifact, exactly as your own app would:

```groovy
implementation 'com.configdirector:configdirector-server-sdk:1.0.0'
```

Pass `-PuseLocalSdk` to build it against
[`configdirector-server-sdk/`](../../../configdirector-server-sdk/) in this repository instead:

```bash
./gradlew :samples:configdirector-server-sdk:micronaut:run -PuseLocalSdk
```

That is how to try an unreleased SDK change against a real consumer, and CI sets it on every job
so a breaking API change fails here before it ships.

## It needs a JDK 25 — and so does the build

Micronaut 5 ships Java 25 bytecode, so this module compiles and runs on 25:

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
```

A toolchain is not the whole story, though. **Gradle itself has to run on 25** for this module,
because the Micronaut Gradle plugin is also Java 25 bytecode and is loaded into the Gradle daemon
rather than into a toolchain. On an older JVM the build fails during configuration, before any
compilation is attempted:

```
> Could not resolve io.micronaut.gradle:micronaut-gradle-plugin:5.0.2.
   > Dependency requires at least JVM runtime version 25. This build uses a Java 21 JVM.
```

That is why this repository's CI runs on JDK 25. If `./gradlew` picks an older JVM on your
machine, point `JAVA_HOME` at a 25 installation.

None of this changes the SDK. **Its floor is still Java 17** — it is compiled with
`options.release = 17`, and the [`spring-boot`](../spring-boot/) sample is the one that keeps that
floor honest by compiling against the SDK on 17. This sample cannot, because nothing below 25 can
read Micronaut 5's class files.

## The client is a singleton

This is the single most important thing the sample shows, so it lives in its own class:
[`ConfigDirectorFactory`](src/main/java/com/configdirector/samples/micronaut/ConfigDirectorFactory.java).
Create one client when the application starts, share it for the whole lifetime of the process, and
close it on shutdown.

A Micronaut `@Factory` method is exactly that — the container builds it once and injects the same
instance everywhere:

```java
@Context
@Bean(preDestroy = "close")
public ConfigDirectorClient configDirectorClient(SampleConfiguration configuration) {
  ConfigDirectorClient client = ConfigDirector.client(configuration.getServerKey(), options -> ...);
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

`preDestroy = "close"` is what replaces Python's `atexit`: Micronaut calls it when the context
stops, which covers Ctrl-C, a container SIGTERM, and a failed startup alike.

`@Context` rather than `@Singleton` is the other half. A plain `@Singleton` is built on first
injection, which here would be the first request that needs a config — that request would pay for
`initialize()`, and every request before the client existed would have been served defaults by a
client that had not been asked to connect yet. A context-scoped bean is built with the application
context, so the cost lands at startup where it belongs.

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

**Settings come from the environment.** `configdirector.*` binds onto
[`SampleConfiguration`](src/main/java/com/configdirector/samples/micronaut/SampleConfiguration.java),
so a real deployment supplies them as environment variables rather than editing code:

| Variable                    | Default           | Meaning                                |
| --------------------------- | ----------------- | -------------------------------------- |
| `CONFIGDIRECTOR_SERVER_KEY` | `fake-sample-key` | Your server SDK key. A secret.         |
| `CONFIGDIRECTOR_BASE_URL`   | _(none)_          | Only when routing through a proxy.     |
| `CONFIGDIRECTOR_MODE`       | `streaming`       | `streaming` or `polling`. |
| `CONFIGDIRECTOR_TIMEOUT`    | `3s`              | Initialization timeout.                |
| `CONFIGDIRECTOR_LOG_LEVEL`  | `INFO`            | Set to `DEBUG` to trace evaluations.   |

Note what `application.properties` does _not_ contain: any `${CONFIGDIRECTOR_SERVER_KEY}`
placeholder. Micronaut's environment property source already understands the
`SCREAMING_SNAKE_CASE` spelling of a property name, so `CONFIGDIRECTOR_SERVER_KEY` overrides
`configdirector.server-key` on its own and the file only has to state the default:

```properties
configdirector.server-key=fake-sample-key
```

The one exception is `logger.levels.sample.configdirector`, whose name is not derived from the
variable, so that line does spell the placeholder out.

## Where the `.env` file comes in

Micronaut has no `.env` format of its own, and this sample adds no library for one. What it does
have is a property-source convention, which is enough on its own:
[`DotEnvPropertySource`](src/main/java/com/configdirector/samples/micronaut/DotEnvPropertySource.java)
reads the file into a map and declares `PropertyConvention.ENVIRONMENT_VARIABLE`:

```java
@Override
public PropertyConvention getConvention() {
  return PropertyConvention.ENVIRONMENT_VARIABLE;
}
```

That is the whole trick. Micronaut then applies to the file's keys the same name mangling it
applies to the real environment, so `CONFIGDIRECTOR_SERVER_KEY=...` in `.env` resolves
`configdirector.server-key` exactly as the exported variable would — no per-key mapping to keep in
sync.

[`Application`](src/main/java/com/configdirector/samples/micronaut/Application.java) adds it to the
context:

```java
Micronaut.build(args)
    .mainClass(Application.class)
    .propertySources(DotEnvPropertySource.load(".env"))
    .start();
```

**Put `.env` next to this README**, at `samples/configdirector-server-sdk/micronaut/.env` — the
`run` task sets the module directory as its working directory, and `.env` is resolved relative to
that. An absent file is not an error; the app starts on the defaults in `application.properties`.

Anything in it overrides those defaults:

```bash
CONFIGDIRECTOR_SERVER_KEY=your-real-key
CONFIGDIRECTOR_MODE=polling
CONFIGDIRECTOR_LOG_LEVEL=DEBUG
```

```
INFO  c.c.s.m.ConfigDirectorFactory : ConfigDirector client created once at startup
      (mode=POLLING, ready=false)
DEBUG sample.configdirector : [ConfigDirectorClient] No config state found for
      temporary-feature-flag, returning the default value
```

The startup line is INFO on purpose: Micronaut applies `logger.levels` _after_ it builds
`@Context` beans, so whatever the SDK logs at DEBUG while initializing is still below the
threshold in force at that moment. Evaluations on the request path are late enough to be
unaffected, which is where `DEBUG` earns its keep anyway.

Precedence comes from the source's order, which sits between `application.properties` (`-300`) and
the real environment (`-200`):

```java
private static final int ORDER = EnvironmentPropertySource.POSITION - 50;
```

So a real environment variable still wins over the file — which is what you want in production,
where the platform injects secrets rather than shipping a `.env`:

```bash
CONFIGDIRECTOR_MODE=polling ./gradlew :samples:configdirector-server-sdk:micronaut:run
```

The tests never run `main`, so `DotEnvPropertySource` is not in play there and a `.env` on your
machine cannot change what they assert.

## Running without a server SDK key

Without a valid key the client stays unready and every config falls back to the default this app
passes in. That is the same path a production app takes when it cannot reach ConfigDirector, so it
is worth seeing: the app keeps serving, on the defaults you chose. The smoke tests run in exactly
this state.
