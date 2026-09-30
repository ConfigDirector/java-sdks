# Changelog

Changes to `com.configdirector:configdirector-server-sdk-testing`. Other artifacts published from
this repository keep changelogs of their own.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This artifact is
released together with `configdirector-server-sdk` and always carries the SDK's version, because it
relies on the SDK's internals: a consumer's SDK must be the very same version, which the artifact
checks when a test client is created.

## [Unreleased]

### Added

- `ConfigDirectorTesting.createTestClient`, returning a `TestClient` whose `client()` is a real
  `ConfigDirectorClient` connected to an in-memory server that the test controls: `setValue`,
  `setJsonValue`, `removeValue`, `replaceValues`, `holdInitialization`, `completeInitialization`,
  and `failInitialization`. No network connection is opened and no telemetry is sent.
- `ConfigDirectorTestExtension`, a JUnit Jupiter extension that creates one test client per test,
  seeds it from the repeatable `@BooleanConfigValue`, `@IntegerConfigValue`, `@FloatConfigValue`,
  `@StringConfigValue`, and `@JsonConfigValue` annotations on the test class and method, resolves
  `TestClient` and `ConfigDirectorClient` parameters, and closes the client after the test. It
  works with JUnit 5.10 or newer and JUnit 6.
