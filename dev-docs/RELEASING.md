# Releasing

This describes the existing mechanics. It does not authorize a version choice,
release branch/tag, merge or publication. The source default is a development
snapshot; a validated snapshot is not a selected release version.

## Decision boundary

The maintainer chooses the version, stable/prerelease designation, exact reviewed
SHA and any desired PR/merge route. Use the [qualification](VALIDATION.md) and
[backlog](BACKLOG.md) to distinguish measured behavior, limitations and optional
work. There is no requirement to implement every review suggestion or new CI lane
before a release; unresolved knowledge must not be falsely declared fixed.

Release notes should state compatible Trino/Doris versions and material user
limitations. Golden versions remain fixed unless a separate upgrade is requested.
The upstream Unicode evidence issue does not block publication of a consumer
that keeps LOWER/UPPER local.

## Version and assembly checks

Labels are `<trino>-<major>.<minor>.<patch>` with an optional non-SNAPSHOT SemVer
prerelease, no paths or build metadata. The Trino prefix must match the version
catalog **and the resolved compile artifacts**. Project version is intentionally
overrideable; `gradle.properties` is not the ABI authority.

After the maintainer selects a version, local verification does not publish:

```sh
VERSION='<approved-version>'
mise exec -- ./gradlew validateReleaseVersion verifyPluginAssembly --no-daemon "-Pversion=$VERSION"
```

`pluginAssemble` synchronizes only its owned versioned directory. Verification
compares exact inventory and bytes with the connector/runtime inputs, rejects
duplicate filenames/provided parent-first JARs, checks the Guice boundary, and
requires the ServiceLoader registration. Old versioned directories elsewhere
are not an upgrade procedure and must not be selected by discovery order.

## Existing workflow

**Pushing `release-*` or `release/*` is a publishing action.** Do not use that
trigger just to test CI. The workflow:

1. Derives/validates the branch suffix without shell-source interpolation and
   checks the compiled Trino ABI before live setup.
2. Runs tooling and actual Gradle guards, then the default live build at the
   release version against its disposable Doris stack.
3. Assembles/verifies the exact version, packages ZIP and TAR with one top-level
   `trino-doris-v<version>/` directory and SHA-256 sidecars.
4. Creates the tag/GitHub release at `GITHUB_SHA` and uploads the four assets.

The job explicitly permits fixture mutation, not failover. It does not currently
run slow/overlay or real-plugin smoke automatically; manual qualification is
separate evidence. Those extra automation lanes are not newly imposed gates.

After authorized publication, confirm the tag target and asset names/checksums.
Sidecars check corruption; they are not independent signing/attestation. Action
SHA pinning, narrower workflow permissions, signing and more automation are
separate [backlog decisions](BACKLOG.md), not secretly added release requirements.
