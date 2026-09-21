# ConfigDirector OpenFeature Java Server Provider

[![Actions Status][ci-badge]][ci] [![Maven Central][maven-badge]][maven]

[OpenFeature](https://openfeature.dev) Java server provider for
[ConfigDirector](https://www.configdirector.com), published to Maven Central as
`com.configdirector:configdirector-openfeature-server-provider`. It wraps the
[ConfigDirector Java server SDK](../configdirector-server-sdk/) and requires Java 17 or newer.

## Installation

Gradle:

```groovy
implementation 'com.configdirector:configdirector-openfeature-server-provider:1.0.0'
```

Maven:

```xml
<dependency>
  <groupId>com.configdirector</groupId>
  <artifactId>configdirector-openfeature-server-provider</artifactId>
  <version>1.0.0</version>
</dependency>
```

The OpenFeature Java SDK (`dev.openfeature:sdk`) and the ConfigDirector Java server SDK come with it
as transitive dependencies.

## Retrieve a value

```java
import com.configdirector.openfeature.ConfigDirectorProvider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;

// The server SDK key is a secret. Do not commit it to your source code.
OpenFeatureAPI api = OpenFeatureAPI.getInstance();
api.setProviderAndWait(new ConfigDirectorProvider("YOUR-SERVER-SDK-KEY"));
Client client = api.getClient();

boolean newCheckout = client.getBooleanValue("new-checkout", false);
```

## Additional configuration options

The provider accepts the same settings as the Java server SDK client:

```java
ConfigDirectorProvider provider =
    new ConfigDirectorProvider(
        "YOUR-SERVER-SDK-KEY",
        options -> options.metadata("YOUR-APP-NAME", "1.0.2"));
```

## User context

Targeting rules are evaluated locally, without additional network calls for different contexts.

```java
EvaluationContext context =
    new ImmutableContext(
        "12345", // In OpenFeature, the targeting key represents the context's user ID
        Map.of(
            "name", new Value("Example User"),
            // Any arbitrary traits which can be referenced in targeting rules
            "traits", new Value(new ImmutableStructure(Map.of("region", new Value("North America"))))));

boolean value = client.getBooleanValue("my-config-key", false, context);
```

A boolean `anonymous` attribute keeps the context out of the ConfigDirector dashboard.

## Evaluation details

| ConfigDirector outcome                 | OpenFeature reason | OpenFeature error code |
| -------------------------------------- | ------------------ | ---------------------- |
| A value was found                      | `TARGETING_MATCH`  |                        |
| The config carries no value            | `DEFAULT`          |                        |
| The config key is unknown              | `ERROR`            | `FLAG_NOT_FOUND`       |
| No config state has arrived yet        | `ERROR`            | `PROVIDER_NOT_READY`   |
| The value is not of the requested type | `ERROR`            | `TYPE_MISMATCH`        |

The `variant` of a found value is ConfigDirector's identifier for that value.

## Sample apps

[`samples/configdirector-openfeature-server-provider/`](../samples/configdirector-openfeature-server-provider/)
holds small, runnable applications built on this provider, one each for Spring Boot, Micronaut and
Quarkus. Start with
[`spring-boot`](../samples/configdirector-openfeature-server-provider/spring-boot/):

```bash
./gradlew :samples:configdirector-openfeature-server-provider:spring-boot:bootRun -PuseLocalSdk
```

## Getting Help

- [Ask a question in Discussions](https://github.com/orgs/ConfigDirector/discussions)
- [Contact support](https://www.configdirector.com/support)

[//]: # "links"
[ci-badge]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml/badge.svg
[ci]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml
[maven-badge]: https://img.shields.io/maven-central/v/com.configdirector/configdirector-openfeature-server-provider
[maven]: https://central.sonatype.com/artifact/com.configdirector/configdirector-openfeature-server-provider
