# ConfigDirector Java SDK

[![Actions Status][ci-badge]][ci]

This is the Java server SDK for [ConfigDirector](https://www.configdirector.com), in
[`configdirector-server-sdk/`](configdirector-server-sdk/). More ConfigDirector artifacts for the
JVM will be published from this repository over time, each in a directory of its own.

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

Reach out to us via https://www.configdirector.com/support

[//]: # "links"
[ci-badge]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml/badge.svg
[ci]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml
