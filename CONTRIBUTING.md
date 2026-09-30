# Contributing

Notes for the ConfigDirector team. For help using the SDK, see
[Getting Help](README.md#getting-help).

## Building and testing

Gradle provisions the JDKs the build asks for, so a fresh checkout needs nothing installed but a
JVM.

```bash
./gradlew build                                     # compile, test, javadoc, SpotBugs
./gradlew :configdirector-server-sdk:test --tests '*ConfigDirectorClientTest*'
```

`javac` runs with `-Xlint:all -Werror` and Error Prone's findings are warnings, so an unused
import, a raw type, or an unsuppressed call to a deprecated method fails the build rather than the
review. `javadoc` runs with `-Xwerror` under `check`, so a missing `@param` fails there too.

The samples resolve the SDK from Maven Central. Build them against the working tree instead with:

```bash
./gradlew build -PuseLocalSdk
```

## Releasing

The version lives in exactly one place: `version` in
[configdirector-server-sdk/build.gradle](configdirector-server-sdk/build.gradle). There is no
constant to keep in step with it — the version reported in telemetry is read from the jar manifest.
`configdirector-server-sdk-testing` takes that same version and is released together with the SDK:
it relies on the SDK's internals, so a consumer's SDK must be the very same version, which the
artifact checks from both jar manifests when a test client is created.

1. In [configdirector-server-sdk/CHANGELOG.md](configdirector-server-sdk/CHANGELOG.md) and
   [configdirector-server-sdk-testing/CHANGELOG.md](configdirector-server-sdk-testing/CHANGELOG.md),
   rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD` and open a fresh, empty `## [Unreleased]`
   above it. The testing changelog gets the entry even when nothing in it changed, since a version
   of it is published either way.
2. Bump `version` in `configdirector-server-sdk/build.gradle` to match.
3. Merge both to `main`.
4. Run the [Release configdirector-server-sdk](.github/workflows/release-configdirector-server-sdk.yml)
   workflow against `main`. It is manual (`workflow_dispatch`) by design, and releases whatever
   version `main` currently declares, for the SDK and the testing artifact together.
5. **Release both deployments by hand in the [Central Portal](https://central.sonatype.com).** The
   workflow uploads one signed bundle per artifact and stops there, so a green run is not a
   published version — this is the last chance to look at what is about to become permanent, or to
   drop it. Release the two together: the testing artifact refuses to run against any other SDK
   version, so one without the other leaves consumers stuck.
6. Once the version resolves on Central, bump the three samples to it, including their
   `testImplementation` of the testing artifact once they use it. They deliberately lag the SDK:
   naming a version that is not published yet leaves them unresolvable for anyone who is not
   passing `-PuseLocalSdk`.

### The OpenFeature provider

`configdirector-openfeature-server-provider` is released the same way, with its own `version` in
[configdirector-openfeature-server-provider/build.gradle](configdirector-openfeature-server-provider/build.gradle),
its own [changelog](configdirector-openfeature-server-provider/CHANGELOG.md), and the
[Release configdirector-openfeature-server-provider](.github/workflows/release-configdirector-openfeature-server-provider.yml)
workflow. Its published POM depends on whatever version `configdirector-server-sdk/build.gradle`
declares at that commit, so that SDK version must already resolve on Central before the provider
is released.

Once the provider resolves on Central, bump the three samples under
`samples/configdirector-openfeature-server-provider/` to it.

Each workflow refuses to run when its tag, such as `configdirector-server-sdk-vX.Y.Z`, already exists, and
tags the commit only after the upload succeeds. If a deployment was dropped in the Portal rather
than published, delete that tag before running it again.
