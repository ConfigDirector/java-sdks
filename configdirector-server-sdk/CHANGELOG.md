# Changelog

Changes to `com.configdirector:server-sdk`. Other artifacts published from this
repository keep changelogs of their own.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this artifact
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Offline mode. `ConnectionMode.OFFLINE` runs a client that never connects to ConfigDirector and
  sends nothing, for example in UI tests that pin every flag to a known value. It serves the values
  in a JSON file, given with `ConnectionOptions.Builder.file(Path)`, or given inline with
  `values(Map<String, ?>)`; `ConnectionOptions.file()` and `values()` return them. Each value takes
  its config type from the value itself: a boolean, a whole number (`Integer` or `Long` inline), any
  other number (`Float` or `Double` inline), a string, or an object or array (a `Map` with `String`
  keys or a `List` inline). Every context receives the same value, and a value read as another type
  returns the in-code default value, as it does against ConfigDirector. With neither a file nor
  values, every config returns its in-code default value.
- An offline client is ready as soon as `initialize()` returns, and fires `onClientReady` once. A
  value it serves is evaluated with the reason `FOUND_MATCH`. A watch registered before
  `initialize()` is called once with the offline value; calling `initialize()` again loads the
  values, and reads the file, again.
- `ConfigDirector.client(Consumer<ClientOptions>)` builds a client without a server SDK key, which
  only offline mode allows, and `ConfigDirector.client(String, Consumer<ClientOptions>)` accepts a
  null or blank key in offline mode. Every other mode still requires the key and throws
  `ConfigDirectorValidationException` without one.
- `ConfigDirectorInitializationException`, thrown by `initialize()` when an offline client cannot
  load its values: the file is missing, unreadable, not a JSON object or holds a value such as
  `null`; an inline value is null or of another type; both a file and values were given; or either
  was given in `STREAMING` or `POLLING` mode. The client stays not ready, and opens no connection. A
  client that was ready keeps the values it loaded before.

### Changed

- In offline mode, `ConnectionOptions.Builder.build()` no longer checks the timeout, the polling
  interval or the URL, and `initialize(Duration)` ignores its argument, since an offline client
  uses none of them.
- `ConfigDirector.client(null)` no longer compiles, because `null` now matches both one-argument
  forms. `ConfigDirector.client((String) null)` throws `ConfigDirectorValidationException` as
  before.

## [1.9.0] - 2026-10-14

### Added

- Contexts can say what kind of entity they represent: `Context.Builder.kind(String)` sets it and
  `Context.kind()` returns it, for example `device`, `organization` or `tenant`. Leaving it out, or
  setting null, means `user`, and `kind()` then returns null. A kind has 1 to 64 characters, using
  only lowercase letters, digits, `-` and `_`, and is used exactly as given, without trimming or
  changing its case.
- Targeting rules and segments can match on a context's kind. Percentage rollouts give contexts of
  other kinds assignments of their own, while `user` contexts keep the ones they have today.
- A context with an invalid kind never makes the SDK throw, and building one never fails. Getters,
  watchers and `getAllConfigs` evaluate exactly as if no context had been supplied, and
  `ConfigEvaluation.context()` is null. The SDK logs an error through the configured logger for each
  distinct invalid kind once per telemetry flush interval; past 20 distinct kinds in an interval it
  logs once that further kinds are not logged individually. Once telemetry stops, after a fatal
  response or `close()`, the interval no longer resets: each of the first 20 distinct invalid kinds
  is logged once for the rest of the client's life, followed by that same single notice.
- Telemetry reports how many evaluations were given a context with an invalid kind, with a sample of
  each kind cut to 128 characters, at most 20 samples per report, and never the context's
  identifier, name or traits. Captured contexts are reported per kind and identifier, so user `42`
  and device `42` are reported separately, and evaluation events carry the context's kind.

### Changed

- `Context.equals`, `hashCode` and `toString` include the kind. `toString` now reads
  `Context[id=…, kind=…, name=…, traits=…, anonymous=…]`, with `kind=null` for a context without a
  kind, and a context without a kind is not equal to one whose kind is `user`, although both
  evaluate the same.

## [1.8.1] - 2026-10-05

### Fixed

- A segment change now reaches watchers. When an update from the server carries a segment, the
  watchers of every config whose targeting rules use that segment are called with the newly
  evaluated value, and `ConfigsUpdatedEvent.keys()` lists those configs beside the configs the
  update carried. Before, editing a segment or one of its environment overrides changed the values
  served without calling any watcher, and `onConfigsUpdated` handlers received no keys.

## [1.8.0] - 2026-10-04

### Added

- Targeting rules can now use segments. The SDK reads the `segments` section of the server payload
  and evaluates segment conditions (`in` / `not in` a segment) locally, combined by AND with the
  rule's other conditions as every condition already is. Segments received in a full update
  replace the ones held, and a delta adds to them. ConfigDirector only sends rules with segment
  conditions to SDK versions that evaluate them, so upgrading is what makes rules that use segments
  apply to this application.

### Changed

- The artifact is now published as `com.configdirector:server-sdk` from
  `https://maven.configdirector.com`, instead of as `com.configdirector:configdirector-server-sdk`
  on Maven Central, where its last version is 1.7.0. Upgrading means adding the repository to the
  build and changing the artifactId; the README shows both.

## [1.7.0] - 2026-10-01

### Added

- An in-memory connection, `com.configdirector.internal.client.InMemoryConnection`, that builds a
  real client over an in-memory transport with telemetry switched off, for the upcoming
  `configdirector-server-sdk-testing` artifact. It is internal: use it through that artifact.
- `ConfigsUpdatedEvent.removedKeys()`: the keys a full update no longer carried, sorted, so a
  handler can tell a config that was removed from one that was updated. `keys()` still lists only
  the keys the update carried. The one-argument `ConfigsUpdatedEvent(List<String> keys)`
  constructor is kept, so existing calls compile; a record pattern over the single component does
  not.

### Fixed

- `getInteger` and `watchInteger` now return the default value with reason `INVALID_NUMBER` for a
  whole number outside the `int` range, as the Android SDK does. Before, the value wrapped around
  silently with reason `FOUND_MATCH`, so `3000000000` read as `-1294967296`. `getValue` with a `Long`
  default still reads such values.
- A watch on a config that a full update no longer carries is now called with its default value,
  as Swift and Flutter already did. Before, the config silently stopped being served and the watch
  kept its last value.

### Changed

- Default a percentage rollout to bucket 0 when there is no context identifier provided, rather than
  randomly assign on each evaluation.
- The polling interval defaults to 5 minutes with a minimum of 60 seconds. A `pollingInterval`
  below the minimum is now raised to the minimum with a warning when a polling client is built,
  instead of `ConnectionOptions.Builder.build()` throwing. `ConnectionOptions.pollingInterval()`
  returns the value as configured.

## [1.6.1] - 2026-09-26

### Fixed

- The telemetry report now carries the application name and version given through
  `ClientOptions.metadata`, as the config requests already did, so the dashboard can show the
  SDK's activity per application in its activity graphs.

## [1.6.0] - 2026-09-25

### Added

- `EvaluationReason.TYPE_MISMATCH`, reported when a config holds a type the default did not ask
  for. It is spelled `type-mismatch` on the wire, as the other SDKs already spell it.

### Changed

- A boolean, integer, or float config requested as a `String` now evaluates to the default value
  with the `TYPE_MISMATCH` reason, instead of the value's text with `FOUND_MATCH`. Reading a JSON
  config as a `String` still returns its raw document.

## [1.5.0] - 2026-09-23

### Fixed

- Fix bug in conditional rule evaluation incorrectly evaluating multiple conditions in an OR instead of AND.

## [1.4.0] - 2026-09-21

### Changed

- Allow consuming SDKs, like the OpenFeature provider, to adjust the SDK identity used to identify against the SDK server.

## [1.3.0] - 2026-09-20

### Fixed

- HTTP 429 response codes from the SDK server are no longer treated as a fatal error. These need to be handled as transient errors so the client continues to retry and reconnects once the rate limit is cleared.

## [1.2.0] - 2026-09-05

### Changed

- The polling interval defaults to 5 minutes, up from 60 seconds, and `ConnectionOptions` now
  refuses an interval shorter than 60 seconds at build time.

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
