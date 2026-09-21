# Quarkus sample (OpenFeature)

A minimal [Quarkus](https://quarkus.io) app using the
[ConfigDirector OpenFeature provider](../../../configdirector-openfeature-server-provider/). It is
the same app as the [quarkus sample for the Java server SDK](../../configdirector-server-sdk/quarkus/)
-- a single `/configs` endpoint that evaluates a handful of configs and returns them as JSON -- with
every value read through an OpenFeature client instead of the ConfigDirector client.

## Running it

```bash
./gradlew :samples:configdirector-openfeature-server-provider:quarkus:quarkusRun
```

To point it at a real ConfigDirector environment, copy the template and fill in your key:

```bash
cd samples/configdirector-openfeature-server-provider/quarkus
cp .env.example .env
```

`.env` is gitignored — never commit a real key.

Then:

```bash
curl 'http://localhost:3612/configs?id=user-123&plan=pro'
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

Run the smoke tests with `./gradlew :samples:configdirector-openfeature-server-provider:quarkus:test`.

## Which provider it builds against

By default the sample depends on the released artifact, exactly as your own app would:

```groovy
implementation 'com.configdirector:configdirector-openfeature-server-provider:1.1.0'
```

The OpenFeature Java SDK and the ConfigDirector Java server SDK come with it as transitive
dependencies.

Pass `-PuseLocalSdk` to build it against
[`configdirector-openfeature-server-provider/`](../../../configdirector-openfeature-server-provider/)
in this repository instead:

```bash
./gradlew :samples:configdirector-openfeature-server-provider:quarkus:quarkusRun -PuseLocalSdk
```

That is how to try an unreleased change against a real consumer, and CI sets it on every job so a
breaking API change fails here before it ships.

## The provider is registered once

[`OpenFeatureProducer`](src/main/java/com/configdirector/samples/openfeature/quarkus/OpenFeatureProducer.java) registers the provider with OpenFeature when the
application starts and shuts OpenFeature down when it stops. The provider owns one ConfigDirector
client, which holds a connection and the config state, so registering a provider per request would
open a connection every time and serve defaults while each one connects.

A CDI producer method is called once and its result injected everywhere. `@Startup` builds it at
application startup instead of on the first request, and the `@Disposes` method closes the
provider, and with it the ConfigDirector client, when the container stops:

```java
@Produces
@Singleton
@Startup
public OpenFeatureAPI openFeatureApi(SampleConfig config) {
  OpenFeatureAPI api = OpenFeatureAPI.getInstance();
  api.setProviderAndWait(new ConfigDirectorProvider(config.serverKey(), options -> ...));
  return api;
}

@Produces
@Singleton
public Client openFeatureClient(OpenFeatureAPI api) {
  return api.getClient();
}

public void shutdown(@Disposes OpenFeatureAPI api) {
  api.shutdown();
}
```

`setProviderAndWait` blocks until the first config state arrives or the timeout elapses. It does not
throw when ConfigDirector is unreachable: the app keeps serving on the defaults it passes in, and
the provider keeps connecting in the background.

## What else it demonstrates

**The evaluation context is per request.** [`ConfigsResource`](src/main/java/com/configdirector/samples/openfeature/quarkus/ConfigsResource.java) maps query
parameters onto an OpenFeature `EvaluationContext`. `id` becomes the targeting key, `name` and
`anonymous` map to the attributes of the same name, and anything else becomes an entry in the
`traits` structure, which is where targeting rules look for traits:

```
/configs?id=user-123&name=Ada&plan=pro&region=eu
```

**Evaluation is local.** Every `getBooleanValue()` and friends reads config state the provider
already holds in memory, with no network call on the request path.

**Object values are OpenFeature `Value`s.** A JSON config is read with `getObjectValue`, whose
default decides the shape: a structure for a JSON object, a list for a JSON array.

**Configuration changes are events.** The sample logs the keys each update carried through
`onProviderConfigurationChanged`.

**Logging is yours to configure.** The sample passes its own SLF4J logger to the provider, so SDK
output lands under `sample.configdirector`. Set `CONFIGDIRECTOR_LOG_LEVEL=DEBUG` to watch every
evaluation as it happens.

## Settings

| Variable                    | Default           | Meaning                                                  |
| --------------------------- | ----------------- | -------------------------------------------------------- |
| `CONFIGDIRECTOR_SERVER_KEY` | `fake-sample-key` | Your server SDK key. A secret.                           |
| `CONFIGDIRECTOR_BASE_URL`   | _(none)_          | Only when routing through a proxy.                       |
| `CONFIGDIRECTOR_MODE`       | `streaming`       | `streaming` or `polling`.                                |
| `CONFIGDIRECTOR_TIMEOUT`    | `3s`              | Initialization timeout, as a duration Quarkus can parse. |
| `CONFIGDIRECTOR_LOG_LEVEL`  | `INFO`            | Set to `DEBUG` to trace evaluations.                     |

## Where the `.env` file comes in

**Put `.env` next to this README.** The run task uses the module directory as its working
directory.

Quarkus reads `.env` from the working directory on its own, and MicroProfile Config already
understands the `SCREAMING_SNAKE_CASE` spelling of every `configdirector.*` key. A real environment
variable still wins over the file. The test task runs from a directory with no `.env` in it, so a
file on your machine cannot change what the tests assert.

## Running without a server SDK key

Without a valid key the provider never receives config state and every value falls back to the
default this app passes in. That is the same path a production app takes when it cannot reach
ConfigDirector. The smoke tests run in exactly this state.
