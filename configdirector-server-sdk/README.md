# ConfigDirector Java Server SDK

[![Actions Status][ci-badge]][ci]

The Java server SDK for [ConfigDirector](https://www.configdirector.com), published to Maven
Central as `com.configdirector:configdirector-server-sdk`. It requires Java 17 or newer.

This is one of several ConfigDirector artifacts for the JVM published from
[this repository](https://github.com/ConfigDirector/java-sdks), each in a directory of its own.

## Installation

Gradle:

```groovy
implementation 'com.configdirector:configdirector-server-sdk:1.2.0'
```

Maven:

```xml
<dependency>
  <groupId>com.configdirector</groupId>
  <artifactId>configdirector-server-sdk</artifactId>
  <version>1.2.0</version>
</dependency>
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
