# ConfigDirector Java SDK

[![CI][ci-badge]][ci] [![Maven Central][maven-badge]][maven]

Java server SDK for [ConfigDirector](https://www.configdirector.com), remote config and feature flags with typed values, JSON Schema validation, and safe renames of live flags. Start free, no card required.

The SDK lives in [`configdirector-server-sdk/`](configdirector-server-sdk/). The [OpenFeature](https://openfeature.dev) provider that wraps it lives in [`configdirector-openfeature-server-provider/`](configdirector-openfeature-server-provider/). More ConfigDirector artifacts for the JVM will be published from this repository over time, each in a directory of its own.

## Install

```kotlin
dependencies {
    implementation("com.configdirector:configdirector-server-sdk:1.2.0")
}
```

```xml
<dependency>
  <groupId>com.configdirector</groupId>
  <artifactId>configdirector-server-sdk</artifactId>
  <version>1.2.0</version>
</dependency>
```

## Retrieve a value

```java
import com.configdirector.ConfigDirector;
import com.configdirector.ConfigDirectorClient;

// The server SDK key is a secret. Do not commit it to your source code.
ConfigDirectorClient client = ConfigDirector.client("YOUR-SERVER-SDK-KEY");
client.initialize();

boolean newCheckout = client.getBoolean("new-checkout", false);
```

Full details are in the [official documentation](https://docs.configdirector.com/sdks/server/java).

## Documentation

Refer to the [official documentation for the Java SDK](https://docs.configdirector.com/sdks/server/java).

There is also [a quickstart guide for ConfigDirector and any of our SDKs](https://docs.configdirector.com/getting-started/quickstart).

## Sample apps

[`samples/`](samples/) holds small, runnable applications, grouped by the artifact they are built
on. They are the same app in each framework -- a single `/configs` endpoint -- so they can be read
side by side.

[`samples/configdirector-server-sdk/spring-boot`](samples/configdirector-server-sdk/spring-boot/):

```bash
./gradlew :samples:configdirector-server-sdk:spring-boot:bootRun
```

[`samples/configdirector-server-sdk/micronaut`](samples/configdirector-server-sdk/micronaut/):

```bash
./gradlew :samples:configdirector-server-sdk:micronaut:run
```

[`samples/configdirector-server-sdk/quarkus`](samples/configdirector-server-sdk/quarkus/):

```bash
./gradlew :samples:configdirector-server-sdk:quarkus:quarkusRun
```

## Getting Help

- [Ask a question in Discussions](https://github.com/orgs/ConfigDirector/discussions)
- [Contact support](https://www.configdirector.com/support)

[//]: # "links"
[ci-badge]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml/badge.svg
[ci]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml
[maven-badge]: https://img.shields.io/maven-central/v/com.configdirector/configdirector-server-sdk
[maven]: https://central.sonatype.com/artifact/com.configdirector/configdirector-server-sdk
