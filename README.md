# ConfigDirector Java SDKs Monorepo

[![Actions Status][ci-badge]][ci]

Java SDKs for [ConfigDirector](https://www.configdirector.com), remote config and feature flags with typed values, JSON Schema validation, and safe renames of live flags. Start free, no card required.

Pick the package you need from the table below; each one has its own README with an install command and a first example, and the [quickstart](https://docs.configdirector.com/getting-started/quickstart) walks through the first flag end to end. Every package is published to the [ConfigDirector Maven repository](https://docs.configdirector.com/sdks/maven-repository), `https://maven.configdirector.com`.

| Server SDKs                                                                                 | Latest version                                                     | Docs                                                  |
| ------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ | ----------------------------------------------------- |
| [com.configdirector:server-sdk](configdirector-server-sdk/README.md)                        | [![Latest version][server-sdk-version-badge]][maven-repository]    | https://docs.configdirector.com/sdks/server/java      |
| [com.configdirector:openfeature-server-provider](configdirector-openfeature-server-provider/README.md) | [![Latest version][open-server-version-badge]][maven-repository] | https://docs.configdirector.com/sdks/openfeature/java |

## Sample apps

[`samples/`](samples/) holds small, runnable applications, grouped by the package they are built on. They are the same app in each framework -- a single `/configs` endpoint -- so they can be read side by side.

| Package                                        | Spring Boot                                                                    | Micronaut                                                                  | Quarkus                                                                |
| ---------------------------------------------- | ------------------------------------------------------------------------------ | -------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| `com.configdirector:server-sdk`                  | [spring-boot](samples/configdirector-server-sdk/spring-boot/)                  | [micronaut](samples/configdirector-server-sdk/micronaut/)                  | [quarkus](samples/configdirector-server-sdk/quarkus/)                  |
| `com.configdirector:openfeature-server-provider` | [spring-boot](samples/configdirector-openfeature-server-provider/spring-boot/) | [micronaut](samples/configdirector-openfeature-server-provider/micronaut/) | [quarkus](samples/configdirector-openfeature-server-provider/quarkus/) |

## Getting Help

- [Ask a question in Discussions](https://github.com/orgs/ConfigDirector/discussions)
- [Contact support](https://www.configdirector.com/support)

[//]: # "links"
[ci-badge]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml/badge.svg
[ci]: https://github.com/ConfigDirector/java-sdks/actions/workflows/configdirector-server-sdk.yml
[server-sdk-version-badge]: https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fmaven.configdirector.com%2Fcom%2Fconfigdirector%2Fserver-sdk%2Fmaven-metadata.xml&label=maven
[open-server-version-badge]: https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fmaven.configdirector.com%2Fcom%2Fconfigdirector%2Fopenfeature-server-provider%2Fmaven-metadata.xml&label=maven
[maven-repository]: https://docs.configdirector.com/sdks/maven-repository
