# Changelog

Changes to `com.configdirector:openfeature-server-provider`. Other artifacts
published from this repository keep changelogs of their own.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this artifact
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- A segment change now emits `PROVIDER_CONFIGURATION_CHANGED` with the affected flags in
  `flagsChanged`: every flag whose targeting rules use the changed segment. Before, editing a
  segment or one of its environment overrides emitted the event with an empty `flagsChanged`.
  Requires the `com.configdirector:server-sdk` release that reports those flags in
  `ConfigsUpdatedEvent.keys()`.

## [1.5.0] - 2026-10-04

### Added

- Targeting rules can now use segments: the provider evaluates segment conditions through the
  server SDK it depends on, which reads the payload's segments. ConfigDirector only sends rules
  with segment conditions to provider versions that evaluate them, so upgrading is what makes rules
  that use segments apply to this application. Requires version 1.8.0 of
  `com.configdirector:server-sdk`, which the provider's POM declares.

### Changed

- The artifact is now published as `com.configdirector:openfeature-server-provider` from
  `https://maven.configdirector.com`, instead of as
  `com.configdirector:configdirector-openfeature-server-provider` on Maven Central, where its last
  version is 1.4.0. Upgrading means adding the repository to the build and changing the artifactId;
  the README shows both.

## [1.4.0] - 2026-10-01

### Changed

- `PROVIDER_CONFIGURATION_CHANGED` now lists the flags a full update removed in `flagsChanged`,
  after the flags the update carried. Before, a removed flag was not reported as changed. Requires
  version 1.7.0 of `configdirector-server-sdk`, which adds `ConfigsUpdatedEvent.removedKeys()`.

- The polling interval defaults to 5 minutes with a minimum of 60 seconds. A `pollingInterval`
  below the minimum is now raised to the minimum with a warning when a polling client is built,
  instead of being rejected.

## [1.3.1] - 2026-09-26

### Changed

- Include app name and version in telemetry reports to power activity graphs.

## [1.3.0] - 2026-09-25

### Changed

- A boolean, integer, or float config requested as a `String` now evaluates to the default value
  with the `TYPE_MISMATCH` reason. Reading a JSON config as a `String` still returns its raw document.

## [1.2.0] - 2026-09-23

### Fixed

- Fix bug in conditional rule evaluation incorrectly evaluating multiple conditions in an OR instead of AND.

## [1.1.0] - 2026-09-21

### Changed

- The provider now identifies itself to ConfigDirector as `java-openfeature-server-provider` with
  its own version, in the `User-Agent` header and in the SDK name and version it reports, instead
  of as `java-server-sdk`.

## [1.0.0] - 2026-09-20

### Added

- `ConfigDirectorProvider`, an OpenFeature provider backed by the ConfigDirector Java server SDK.
  It resolves boolean, string, integer, long, double and object values, maps the OpenFeature
  evaluation context onto the ConfigDirector context, reports OpenFeature reasons and error codes,
  and emits `PROVIDER_READY` and `PROVIDER_CONFIGURATION_CHANGED` events.
