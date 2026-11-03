# Validation

## Trino 483 compatibility release

This branch derives from published `v484-0.4.0` (`f4a1da9`). The maintainer
explicitly authorized reuse of the completed Doris 4.1.5 qualification and
requested **no requalification**. The [483 adaptation](TRINO-483.md) receives
compilation, resolved-ABI, assembly and archive checks only, including test-source
compilation without execution. The measurements below remain **484 results**;
they are not relabeled as a new 483 full/live run. No CI automation is changed.

## Qualified state

Source checkpoint **`5915a25`** is qualified on Trino 484, Doris
4.1.5-release-917b20be838, Connector/J 26.7.0, metadata 0.21.0, Kotlin 2.4.10 and
JDK 25. Measurements are Linux/amd64; multi-architecture image pins do not certify
an ARM runtime or arbitrary version combinations.

| Lane | Completed result |
|---|---|
| Clean single-FE build, slow cancellation enabled | 403 discovered: 398 passed, five overlay-only skips, zero failures/errors |
| Separate three-FE replay | Five passed, zero skips/failures/errors |
| Targeted resolved-finding replay | Five passed |
| Tooling contracts | Six passed |
| Actual Gradle guard qualification | Four passed, including intentional no-permission refusal |
| ZIP/TAR checksum and extraction | Both pass; 79 input JARs reproduced byte-for-byte |
| ZIP-extracted plugin in fresh Trino 484 | 19 SQL operations pass, including expected configuration errors |

This is **staged qualification**, not one all-at-once no-skip run. The full
single-FE build completed in 16m28s. The five skips are exactly the optional
overlay class, which was replayed explicitly with mutation/failover permission.
Focused counts overlap and are not added to the aggregate as unique cases.

The packaged smoke checks the isolated ServiceLoader classpath, timeout/URL
configuration, positive-TTL statistics policy in both enabled/disabled use,
DNS-SRV option-form catalog creation without lookup, and representative
Unicode/LIKE/outer-join/AVG safeguards. The direct mysql+srv configuration mismatch
found by the initial smoke was fixed and qualification rerun, not waived.

The frozen [runtime-contract report](archive/REPORT-trino-484-runtime-contracts.md)
contains the commands, failed-attempt qualifications, intermediate commit IDs,
artifact details and local evidence locations. Current docs/KDoc cleanup does
not change runtime behavior. All seven validation containers were stopped and
task temporary files cleared; fixture volumes and evidence were retained.

Documentation maintenance passed **39 pure unit checks**, detekt and 79-JAR
assembly verification with both live permissions absent. Manual checks found
zero missing local Markdown links, verified retention of all 47 relocated
records, and compared executable tokens/configuration against the qualified
source. Only comments/paths and one non-routing evidence-description string
changed outside Markdown; no admission, SQL generation, golden pin, workflow or
CI trigger was changed. This maintenance check is not a new CI automation lane.

Since that checkpoint the registry check moved from catalog creation to a unit
test and its dependency to test scope, so the assembled plugin has 76 JARs
(no brikk-sql-metadata or kotlinx-serialization). That change has not been
through the qualification above.

## Repeating qualification

Use [Development](DEVELOPMENT.md) for commands and authorization. Required live
lanes fail loudly if their identified cluster is unsafe/unavailable. Optional
overlay tests skip without an explicit failover request, even if ports are live.
Never convert an OOM, truncated XML or an isolated rerun into a green full-suite
claim. Keep results/commands and skip reasons separate by lane.

The targeted replay cases are covered by the normal test source set; use `--tests`
as described in Development, without a separate probe source set or init script.

Use whole-operation local oracles and selective ordered limits where relevant;
expression-only opt-out is not the same as disabling every connector pushdown.
Recheck composition boundaries: rendered text with grouping/TopN, projections
with renamed columns, NULL-sensitive aggregates, cache hits with session policy,
and cancellation with HA/failure/cleanup schedules.

## What CI does today

CI runs on main pushes/pull requests. Existing jobs run tooling/Gradle guards,
then the default live build against their explicitly authorized disposable stack.
They do not enable the slow cancellation tag or start the optional overlay.
The release job uses the chosen version and the same default live gate before
packaging/publication. The results above are local qualification, not a claim
that an untriggered branch push received a green GitHub Actions run.

Scheduled slow/overlay lanes and an automated real-plugin smoke are optional
review follow-ups, **not new required CI work**. No such job, runner, secret or
publication trigger was added by the runtime-contract fixes.
