# Spring Boot sample (OpenFeature)

A minimal [Spring Boot](https://spring.io/projects/spring-boot) app using the
[ConfigDirector OpenFeature provider](../../../configdirector-openfeature-server-provider/). It is
the same app as the [spring-boot sample for the Java server SDK](../../configdirector-server-sdk/spring-boot/)
-- a single `/configs` endpoint that evaluates a handful of configs and returns them as JSON -- with
every value read through an OpenFeature client instead of the ConfigDirector client.

## Running it

```bash
./gradlew :samples:configdirector-openfeature-server-provider:spring-boot:bootRun
```

To point it at a real ConfigDirector environment, copy the template and fill in your key:

```bash
cd samples/configdirector-openfeature-server-provider/spring-boot
cp .env.example .env
```

`.env` is gitignored — never commit a real key.

Then:

```bash
curl 'http://localhost:3610/configs?id=user-123&plan=pro'
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

Run the smoke tests with `./gradlew :samples:configdirector-openfeature-server-provider:spring-boot:test`.

## Which provider it builds against

By default the sample depends on the released artifact, exactly as your own app would:

```groovy
implementation 'com.configdirector:configdirector-openfeature-server-provider:1.3.0'
```

The OpenFeature Java SDK and the ConfigDirector Java server SDK come with it as transitive
dependencies.

Pass `-PuseLocalSdk` to build it against
[`configdirector-openfeature-server-provider/`](../../../configdirector-openfeature-server-provider/)
in this repository instead:

```bash
./gradlew :samples:configdirector-openfeature-server-provider:spring-boot:bootRun -PuseLocalSdk
```

That is how to try an unreleased change against a real consumer, and CI sets it on every job so a
breaking API change fails here before it ships.

## The provider is registered once

[`OpenFeatureConfiguration`](src/main/java/com/configdirector/samples/openfeature/springboot/OpenFeatureConfiguration.java) registers the provider with OpenFeature when the
application starts and shuts OpenFeature down when it stops. The provider owns one ConfigDirector
client, which holds a connection and the config state, so registering a provider per request would
open a connection every time and serve defaults while each one connects.

A Spring `@Bean` is built once and injected everywhere, and `destroyMethod = "shutdown"` is what
closes the provider, and with it the ConfigDirector client, when the context stops:

```java
@Bean(destroyMethod = "shutdown")
public OpenFeatureAPI openFeatureApi(SampleProperties properties) {
  OpenFeatureAPI api = OpenFeatureAPI.getInstance();
  api.setProviderAndWait(new ConfigDirectorProvider(properties.getServerKey(), options -> ...));
  return api;
}

@Bean
public Client openFeatureClient(OpenFeatureAPI api) {
  return api.getClient();
}
```

`setProviderAndWait` blocks until the first config state arrives or the timeout elapses. It does not
throw when ConfigDirector is unreachable: the app keeps serving on the defaults it passes in, and
the provider keeps connecting in the background.

## What else it demonstrates

**The evaluation context is per request.** [`ConfigsController`](src/main/java/com/configdirector/samples/openfeature/springboot/ConfigsController.java) maps query
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

| Variable                    | Default           | Meaning                                       |
| --------------------------- | ----------------- | --------------------------------------------- |
| `CONFIGDIRECTOR_SERVER_KEY` | `fake-sample-key` | Your server SDK key. A secret.                |
| `CONFIGDIRECTOR_BASE_URL`   | _(none)_          | Only when routing through a proxy.            |
| `CONFIGDIRECTOR_MODE`       | `streaming`       | `streaming` or `polling`.                     |
| `CONFIGDIRECTOR_TIMEOUT`    | `3s`              | Initialization timeout, as a Spring duration. |
| `CONFIGDIRECTOR_LOG_LEVEL`  | `INFO`            | Set to `DEBUG` to trace evaluations.          |

## Where the `.env` file comes in

**Put `.env` next to this README.** The run task uses the module directory as its working
directory.

Spring Boot has no `.env` format of its own. One line in `application.properties` reads the file
as `KEY=value` pairs, and `optional:` lets the app start without it:

```properties
spring.config.import=optional:file:.env[.properties]
```

A real environment variable still wins over the file. The tests set `spring.config.import=` to
empty, so a `.env` on your machine cannot change what they assert.

## Running without a server SDK key

Without a valid key the provider never receives config state and every value falls back to the
default this app passes in. That is the same path a production app takes when it cannot reach
ConfigDirector. The smoke tests run in exactly this state.
