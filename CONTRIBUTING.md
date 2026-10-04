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

The samples resolve the SDK from the ConfigDirector Maven repository. Build them against the
working tree instead with:

```bash
./gradlew build -PuseLocalSdk
```

## Releasing

Releases go to the ConfigDirector Maven repository, `https://maven.configdirector.com`. The
repository is a Cloudflare R2 bucket; its setup, and a dev twin at
`https://maven.configdirector-dev.com`, are documented in the `config-director` repository under
`infrastructure/cloudflare/maven-repository/`. Each release workflow takes a `repository` input,
`dev` or `prod`, and runs in the matching GitHub environment, `maven-dev` or `maven-prod`, which
holds the bucket's credentials. `maven-prod` requires the product owner's approval before the job
starts, which is the last check before a version becomes permanent.

The version lives in exactly one place: `version` in
[configdirector-server-sdk/build.gradle](configdirector-server-sdk/build.gradle). There is no
constant to keep in step with it — the version reported in telemetry is read from the jar manifest.
`com.configdirector:server-sdk-testing` takes that same version and is released together with the
SDK: it relies on the SDK's internals, so a consumer's SDK must be the very same version, which the
artifact checks from both jar manifests when a test client is created.

1. In [configdirector-server-sdk/CHANGELOG.md](configdirector-server-sdk/CHANGELOG.md) and
   [configdirector-server-sdk-testing/CHANGELOG.md](configdirector-server-sdk-testing/CHANGELOG.md),
   rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD` and open a fresh, empty `## [Unreleased]`
   above it. The testing changelog gets the entry even when nothing in it changed, since a version
   of it is published either way.
2. Bump `version` in `configdirector-server-sdk/build.gradle` to match, and the versions in the
   README install snippets.
3. Merge to `main`.
4. Run the [Release configdirector-server-sdk](.github/workflows/release-configdirector-server-sdk.yml)
   workflow against `main` with `repository` set to `dev`, and check the result against
   `https://maven.configdirector-dev.com`. The workflow is manual (`workflow_dispatch`) by design,
   and releases whatever version `main` currently declares, for the SDK and the testing artifact
   together. It first checks that the version is not in the bucket yet, then builds and tests,
   publishes the signed artifacts into a staging repository under `build/maven-repository`, attests
   every jar and POM, uploads them, and in `prod` tags the commit last. The check and the upload are
   the actions in [maven-repository-actions](https://github.com/ConfigDirector/maven-repository-actions),
   which also documents what the upload guarantees.
5. Run it again with `repository` set to `prod`, and approve the `maven-prod` environment when
   GitHub asks.
6. Once the version resolves from `https://maven.configdirector.com`, bump the three samples to
   it, including their `testImplementation` of the testing artifact. They deliberately lag the SDK:
   naming a version that is not published yet leaves them unresolvable for anyone who is not
   passing `-PuseLocalSdk`.

A version whose POM is already in the bucket is refused before anything is built or uploaded: a
released version is never replaced, so a change after a release needs a new version. A run that
failed before the POM went up can simply be run again.

### The OpenFeature provider

`com.configdirector:openfeature-server-provider` is released the same way, with its own `version`
in
[configdirector-openfeature-server-provider/build.gradle](configdirector-openfeature-server-provider/build.gradle),
its own [changelog](configdirector-openfeature-server-provider/CHANGELOG.md), and the
[Release configdirector-openfeature-server-provider](.github/workflows/release-configdirector-openfeature-server-provider.yml)
workflow. Its published POM depends on whatever version `configdirector-server-sdk/build.gradle`
declares at that commit, so that SDK version must already be in the repository the provider is
released to.

Once the provider resolves from `https://maven.configdirector.com`, bump the three samples under
`samples/configdirector-openfeature-server-provider/` to it.

### Signing

Every published file is signed with the key in [KEYS.md](KEYS.md). The release workflows read it
from the `SIGNING_KEY` and `SIGNING_KEY_PASSWORD` repository secrets.
