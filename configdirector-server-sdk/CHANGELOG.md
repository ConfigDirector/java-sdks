# Changelog

Changes to `com.configdirector:configdirector-server-sdk`. Other artifacts published from this
repository keep changelogs of their own.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this artifact
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] - 2026-08-31

### Added

- A watch per type, mirroring the getters: `watchBoolean`, `watchString`, `watchInteger`,
  `watchDouble`, `watchJsonObject` and `watchJsonArray`. Each comes in the same two forms the
  getters do, with and without a `Context`, and hands the callback a value already in its own type
  rather than one the caller has to narrow.

### Deprecated

- `watch(String, T, Consumer<T>)` and `watch(String, T, Consumer<T>, Context)`, replaced by the
  typed watches above. They still work and are not scheduled for removal; a `Long` or `Float`
  default, which no typed watch covers, still goes through them.

## [1.0.0] - 2026-08-24

### Added

- `ConfigDirector.client(...)`, building a `ConfigDirectorClient` that is safe to share across
  threads. Building one makes no network calls; `initialize()` connects and waits for the first
  config state, bounded either by the configured timeout or by one passed to it.
- Typed getters — `getBoolean`, `getString`, `getInteger`, `getDouble`, `getJsonObject`,
  `getJsonArray` — plus a generic `getValue` that takes its type from the default. Each comes with
  and without a `Context`. A getter returns its default rather than throwing, whether the config is
  unknown, the server unreachable, or the value will not coerce.
- `Context`, carrying `id`, `name`, `traits` and `anonymous` for targeting rules to evaluate
  against, and `Metadata` of `appName` and `appVersion`, which rules can also reference.
- Three connection modes, selected through `ConnectionOptions`: `STREAMING` over server-sent
  events, `POLLING` on an interval, and `ONE_TIME`. Streaming reconnects on its own with a backoff
  capped just under ten minutes, and stops on an unrecoverable status.
- `watch`, `unwatch` and `unwatchAll`, calling back with the newly evaluated value whenever an
  update carries the key.
- `onClientReady`, `onConfigsUpdated` and `onConfigEvaluated`, each returning a `Subscription` that
  cancels the registration. `onConfigEvaluated` publishes every evaluation, including those that
  returned the caller's default, with an `EvaluationReason` saying which it was.
- `getAllConfigs`, evaluating every config the SDK holds — or a named subset — for handing to a
  client SDK to hydrate with. It records no telemetry, since the receiving SDK reports its own
  evaluations.
- Telemetry that aggregates evaluations and reports them off the calling thread, so reading a
  config never waits on the network. `TelemetryOptions` tunes the queue limit and flush interval.
- `close()` and `close(Duration)`. The timed form spends one budget on the whole shutdown rather
  than a separate timeout per step, so it is safe to call from a shutdown hook running under a
  container's termination grace period.
- SLF4J logging under the `com.configdirector` logger, or a `Logger` of your own through
  `ClientOptions.logger`.
- Java 17 bytecode, verified against the Java 17 API rather than merely targeted at it, published
  with an `Automatic-Module-Name` of `com.configdirector`.
