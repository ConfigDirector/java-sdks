# ConfigDirector Java SDKs Monorepo

[![Actions Status][ci-badge]][ci]

Java SDKs for [ConfigDirector](https://www.configdirector.com), remote config and feature flags with typed values, JSON Schema validation, and safe renames of live flags. Start free, no card required.

Pick the package you need from the table below; each one has its own README with an install command and a first example, and the [quickstart](https://docs.configdirector.com/getting-started/quickstart) walks through the first flag end to end.

| Server SDKs                                                                                                           | Maven Central                                                       | Docs                                                           |
| --------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- | -------------------------------------------------------------- |
| [com.configdirector:configdirector-server-sdk](configdirector-server-sdk/README.md)                                   | [![Maven Central][server-sdk-maven-badge]][server-sdk-maven-link]   | https://docs.configdirector.com/sdks/server/java               |
| [com.configdirector:configdirector-openfeature-server-provider](configdirector-openfeature-server-provider/README.md) | [![Maven Central][open-server-maven-badge]][open-server-maven-link] | [README](configdirector-openfeature-server-provider/README.md) |

## Sample apps

[`samples/`](samples/) holds small, runnable applications, grouped by the package they are built on. They are the same app in each framework -- a single `/configs` endpoint -- so they can be read side by side.

| Package                                      | Spring Boot                                                                    | Micronaut                                                                  | Quarkus                                                                |
| -------------------------------------------- | ------------------------------------------------------------------------------ | -------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| `configdirector-server-sdk`                  | [spring-boot](samples/configdirector-server-sdk/spring-boot/)                  | [micronaut](samples/configdirector-server-sdk/micronaut/)                  | [quarkus](samples/configdirector-server-sdk/quarkus/)                  |
| `configdirector-openfeature-server-provider` | [spring-boot](samples/configdirector-openfeature-server-provider/spring-boot/) | [micronaut](samples/configdirector-openfeature-server-provider/micronaut/) | [quarkus](samples/configdirector-openfeature-server-provider/quarkus/) |

## Getting Help

- [Ask a question in Discussions](https://github.com/orgs/ConfigDirector/discussions)
- [Contact support](https://www.configdirector.com/support)

[//]: # "links"
[ci-badge]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml/badge.svg
[ci]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml
[server-sdk-maven-badge]: https://img.shields.io/maven-central/v/com.configdirector/configdirector-server-sdk
[server-sdk-maven-link]: https://central.sonatype.com/artifact/com.configdirector/configdirector-server-sdk
[open-server-maven-badge]: https://img.shields.io/maven-central/v/com.configdirector/configdirector-openfeature-server-provider
[open-server-maven-link]: https://central.sonatype.com/artifact/com.configdirector/configdirector-openfeature-server-provider
