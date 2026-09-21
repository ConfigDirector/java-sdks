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

Full details are in the [official documentation](https://docs.configdirector.com/sdks/openfeature/java).

## Documentation

Refer to the [official documentation for the OpenFeature Java provider](https://docs.configdirector.com/sdks/openfeature/java).

There is also [a quickstart guide for ConfigDirector and any of our SDKs](https://docs.configdirector.com/getting-started/quickstart).

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
