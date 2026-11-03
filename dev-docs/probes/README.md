# Opt-in regression replays

These Kotlin sources are outside the default test source set. The init script
adds them for targeted rechecks against the identified disposable golden cluster.
They now assert the **resolved behavior**, not that the old defects still exist.

| Class | Purpose |
|---|---|
| `TestDorisR04Review` | Outer-join/min_by/max_by NULL behavior under EAGER, with base/INNER controls |
| `TestDorisR05Review` | Reuse the durable real-driver HA abort/identity/fresh-connection regressions |
| `TestDorisRemainingReview` | Positive-TTL statistics policy, literal-token rejection, timeout ranges and URL/default-database rejection |

From the repository root:

```sh
./compose/up.sh
DORIS_TEST_ALLOW_MUTATION=1 mise exec -- ./gradlew \
  -I dev-docs/probes/review-probes.init.gradle test \
  --tests '*TestDorisR04Review' --tests '*TestDorisR05Review' \
  --tests '*TestDorisRemainingReview' --no-daemon
```

The shared [test guard](../DEVELOPMENT.md) requires explicit mutation permission,
disposable Compose ownership/native identity and the golden FE/BE release. These
probes may recreate their documented fixtures; do not point them at production.
The remaining-review replay uses a UUID-named schema and drops only that schema.
Its candidate-classifier test never kills an unrelated live query.

HA URLs using duplicate addresses isolate driver locking/identity on the same FE;
they do not prove cross-FE forwarding or master election. The dedicated optional
overlay lane performs the real failover/cross-FE checks and requires its separate
permission. Latency assertions are regression bounds, not universal production SLAs.

Keep fresh XML/logs by run and interpret scope honestly. The original pre-fix
red measurements are retained in the [archive](../archive/README.md); a successful
targeted replay is not a replacement for full or packaged-plugin qualification.
