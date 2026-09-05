# Temporal range pushdown: E-06

Verified 2026-09-05 against Doris 4.1.3, Trino 483 and Connector/J 9.7.0.

## Verdict

Confirmed wrong results, not merely a theoretical range mismatch. The initial live probe
ran 58 queries with normal pushdown and with Trino's `allow_pushdown_into_connectors=false`.
Every local query succeeded; 30 pushed results differed: 24 wrong row sets and 6 timestamp
projection read failures. The fix keeps unsafe temporal parameters out of remote SQL.

## Fixture and reproduction

`TestDorisTemporalRange` provisions `e06_temporal.t`. Rows 1-3 contain year 0, 2026 and
9999 values in DATE and DATETIME(0/3/6) columns. Rows 4-6 have NULL scalar values.
DATE/DATETIME arrays include ordinary values, NULL elements, a NULL array and an empty array.
The test source contains the complete DDL and inserted values.

Selected pre-fix results (queries select `id` unless noted):

| Expression | Trino-local | Pushed |
|---|---|---|
| `d > DATE '-0001-12-31'` | `[1, 2, 3]` | `[]` |
| `d < DATE '10000-01-01'` | `[1, 2, 3]` | `[]` |
| `d <> DATE '10000-01-01'` | `[1, 2, 3]` | `[]` |
| `coalesce(d, DATE '-0001-12-31') < DATE '10000-01-01'` | `[1, 2, 3, 4, 5, 6]` | `[]` |
| `dt6 < TIMESTAMP '10000-01-01 00:00:00.000000'` | `[1, 2, 3]` | `[]` |
| projection `coalesce(dt6, TIMESTAMP '10000-01-01 00:00:00.000000')` | valid values, with year 10000 for NULL rows | `Cannot convert string '+10000-01-01 00:00:00' to java.time.LocalDateTime value` |
| `contains(ad, DATE '10000-01-01')` | `[]` | `[2, 6]` |
| `array_position(ad, DATE '10000-01-01') = 0` | `[1, 2, 3, 5, 6]` | `[1, 3, 5]` |

The same array failures occurred for DATETIME(6) needles. Scalar comparisons failed for
both negative years and year 10000 across all three tested timestamp precisions.
Timestamp coalesce projections failed for both bounds at each precision. DATE coalesce
projections and the tested nullif projections returned matching values; not every out-of-range
expression failed. The guard is deliberately conservative across the shared binding paths.

A direct Doris probe returned SQL NULL for all four expressions:

```sql
SELECT CAST('-0001-12-31' AS DATE),
       CAST('10000-01-01' AS DATE),
       CAST('-0001-12-31 23:59:59.999999' AS DATETIME(6)),
       CAST('10000-01-01 00:00:00' AS DATETIME(6));
```

That NULL conversion changes comparisons and can turn an otherwise nonmatching array needle
into a match against a NULL element. Projection coercion/read behavior is another failure mode,
as the timestamp decode error demonstrates.

## Resolution

- Shared range check over Trino's native epoch-day/microsecond values, without converting
  arbitrary inputs into Java date/time objects. DATE bounds are year 0 through 9999 inclusive;
  timestamp bounds include year 0 midnight and exclude year 10000 midnight.
- DATE/DATETIME domain controllers inspect every explicit range endpoint/discrete value
  before compaction. Unsafe domains stay local; valid domains retain standard compaction
  behavior. NULL-only/all/none domains do not acquire artificial bounds.
- Scalar constants use the same check before binding, including nested expressions.
- ARRAY contains/array_position needles and arrays_overlap constant-array elements use the
  same guard. These bypass domain controllers, so fixing the mappings alone is insufficient.
- No changes to temporal reads, non-temporal parameters, or valid-boundary pushdown.

## Regression evidence

`TestDorisTemporalRange` checks all six comparisons, IN/NOT IN, nullable domains, BETWEEN,
coalesce/nullif projections and predicates, both array bounds and overlap orientations.
It compares against pushdown-disabled execution and requires retained Filter/Project nodes.
Controls require fully pushed valid-boundary/NULL predicates and valid scalar/array operations.
A remote audit assertion confirms `id = 2` still pushes while the unsafe year-10000 predicate
remains local.

`TestDorisTemporalRangeMapping` covers precise limits, huge Long inputs, timestamp precisions
0/3/6, nullable and unbounded domains, and rejection before domain compaction.

Verification: `mise exec -- ./gradlew build -PwithCancellation` passed in 13m33s:
302 passed, 5 multi-FE overlay skips, zero failures; detekt and plugin assembly passed.
