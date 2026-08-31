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

1. In [configdirector-server-sdk/CHANGELOG.md](configdirector-server-sdk/CHANGELOG.md), rename
   `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD` and open a fresh, empty `## [Unreleased]` above
   it.
2. Bump `version` in `configdirector-server-sdk/build.gradle` to match.
3. Merge both to `main`.
4. Run the [Release configdirector-server-sdk](.github/workflows/release-configdirector-server-sdk.yml)
   workflow against `main`. It is manual (`workflow_dispatch`) by design, and releases whatever
   version `main` currently declares.
5. **Release the deployment by hand in the [Central Portal](https://central.sonatype.com).** The
   workflow uploads a signed bundle and stops there, so a green run is not a published version —
   this is the last chance to look at what is about to become permanent, or to drop it.
6. Once the version resolves on Central, bump the three samples to it. They deliberately lag the
   SDK: naming a version that is not published yet leaves them unresolvable for anyone who is not
   passing `-PuseLocalSdk`.

The workflow refuses to run when the tag `configdirector-server-sdk-vX.Y.Z` already exists, and
tags the commit only after the upload succeeds. If a deployment was dropped in the Portal rather
than published, delete that tag before running it again.
