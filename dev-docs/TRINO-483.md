# Trino 483 compatibility release

The `trino-483` branch starts at published `v484-0.4.0`, source
`f4a1da98f43a822cf678b816380d57c0a4c13ed7`, not the older `main` branch.
It retains all shared read-only, pushdown, timeout, URL, cache, cancellation and
physical-connection fixes.

## Target-specific changes

- Trino BOM/SPI/Base JDBC target **483**; default branch version `483-0.4.0-SNAPSHOT`.
- Use Trino 483's `com.google.inject:guice` **classes** artifact and matching
  assembly guard rather than 484's Airlift Guice fork.
- Remove the unavailable `trino-json` dependencies. JSON/VARIANT use the existing
  483 `JsonTypeUtil.jsonParse` Slice mapping with pushdown disabled, rather than
  484's native `Json` mapping. Object keys sort, duplicate keys collapse to the
  last value, and number text follows the 483 BigDecimal parser. Parser edge cases
  follow Trino 483, not the newer native-Json validator.
- Keep `json_extract_scalar` equality local: Trino 483's parser keeps the last
  duplicate key while Doris extracts the first, so the 484 rewrite is not equivalent.
- Adapt JSON unit-source/block assertions and the target-sensitive integration
  expectations; retain all shared regression sources without running them.
- Point the optional manual sandbox at Trino 483 and update branch usage guidance.

Doris **4.1.5** and its image digests, Connector/J **26.7.0**, metadata **0.21.0**,
Kotlin **2.4.10** and JDK **25** remain unchanged. Other engine-managed dependencies
follow the 483 BOM. The statistics metadata constructors match 483 and need no
adaptation. Registry provenance still identifies its original 484/Doris evidence;
JSON scalar equality is not among the active registry pins on 483.

## Release checks, not requalification

The maintainer explicitly requested no repeated full/live qualification. Reuse
the completed [484/Doris measurements](VALIDATION.md); do not call them a new
483 run. Only compile/ABI/assembly/package checks are performed:

```sh
./gradlew validateReleaseVersion verifyPluginAssembly testClasses \
  -Pversion=483-0.4.0 --no-daemon
```

`testClasses` compiles test sources; it does **not** execute tests. No `build`,
`test`, live suite, Docker startup or new CI job is part of this publication.
ZIP/TAR inventories and SHA-256 sidecars are checked against the verified
assembly. Publish stable `v483-0.4.0` directly at the pushed `trino-483` commit,
not via a `release-*` branch that would start another live build.

The command above passed: production and test sources compiled, the resolved
Trino ABI matched **483**, and the assembly guard verified **79 JARs** plus the
ServiceLoader entry. Inventory confirms `guice-7.0.0-classes.jar`, Connector/J
26.7.0, metadata 0.21.0 and Kotlin stdlib 2.4.10, with only Trino-483 runtime
modules and no provided SPI JARs. No test results were generated or containers
started. Local build/package evidence is retained under
`~/.cache/trino-doris-0.4.0-release/`.
