# ConfigDirector Java Server SDK

[![Actions Status][ci-badge]][ci] [![Latest version][version-badge]][maven-repository]

The Java server SDK for [ConfigDirector](https://www.configdirector.com), published to the
ConfigDirector Maven repository as `com.configdirector:server-sdk`. It requires Java 17 or newer.

This is one of several ConfigDirector artifacts for the JVM published from
[this repository](https://github.com/ConfigDirector/java-sdks), each in a directory of its own.

## Installation

The SDK is published to the ConfigDirector Maven repository, `https://maven.configdirector.com`.
Add the repository to your build once, next to `mavenCentral()`, then add the dependency. If your
build declares its repositories in `settings.gradle` or `settings.gradle.kts` under
`dependencyResolutionManagement`, the repository block goes there instead. The repository, the
signing key and how to verify what it serves are described at
https://docs.configdirector.com/sdks/maven-repository.

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
    implementation("com.configdirector:server-sdk:1.8.0")
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
    implementation 'com.configdirector:server-sdk:1.8.0'
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
  <artifactId>server-sdk</artifactId>
  <version>1.8.0</version>
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

## Test your code

[`server-sdk-testing`](../configdirector-server-sdk-testing/) creates a real client
connected to an in-memory server that your test controls, so the code that reads your configs and
flags is tested without a network connection and without changing production code. It also ships a
JUnit Jupiter extension. It comes from the same repository as the SDK. Add it in test scope, at
the same version as the SDK:

```groovy
testImplementation 'com.configdirector:server-sdk-testing:1.8.0'
```

```java
try (TestClient testClient = ConfigDirectorTesting.createTestClient(Map.of("new-checkout", true))) {
  CheckoutService service = new CheckoutService(testClient.client());
  testClient.client().initialize();

  assertThat(service.isNewCheckoutEnabled("user-123")).isTrue();

  testClient.setValue("new-checkout", false);
  assertThat(service.isNewCheckoutEnabled("user-123")).isFalse();
}
```

## Documentation

Refer to the [official documentation for the Java SDK](https://docs.configdirector.com/sdks/server/java).

There is also [a quickstart guide for ConfigDirector and any of our SDKs](https://docs.configdirector.com/getting-started/quickstart).

## Sample apps

[`samples/configdirector-server-sdk/`](../samples/configdirector-server-sdk/) holds small, runnable
applications built on this SDK. Start with
[`spring-boot`](../samples/configdirector-server-sdk/spring-boot/):

```bash
./gradlew :samples:configdirector-server-sdk:spring-boot:bootRun
```

## Getting Help

Reach out to us via https://www.configdirector.com/support

[//]: # "links"
[ci-badge]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml/badge.svg
[ci]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml
[version-badge]: https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fmaven.configdirector.com%2Fcom%2Fconfigdirector%2Fserver-sdk%2Fmaven-metadata.xml&label=maven
[maven-repository]: https://docs.configdirector.com/sdks/maven-repository
