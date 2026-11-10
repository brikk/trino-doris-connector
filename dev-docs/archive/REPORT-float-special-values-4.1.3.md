# FLOAT special values: E-11

Verified 2026-09-05 against Doris 4.1.3, Trino 483 and Connector/J 9.7.0.

## Confirmed failure

An actual connector scan of stored FLOAT Infinity failed before the fix (ASCII-normalized
diagnostic below):

```text
Value 'infinity' is outside of valid range for type java.lang.Float
```

The driver renders the infinity symbol in the original exception message. The stack reached
`ReadFunction.isNull` -> `ResultSet.getObject` -> Connector/J's `FloatValueFactory` before
the connector's `getFloat` read function even ran. Direct probes showed both `getObject`
and `getFloat` reject positive and negative Infinity. `getString` succeeds for both.

## Probe results

`TestDorisFloatSpecialValues` creates a stored FLOAT column with the following cases:

| Case | JDBC getString | Previous getFloat/getObject | Connector after fix |
|---|---|---|---|
| Positive infinity | `Infinity` | SQLDataException | REAL positive infinity |
| Negative infinity | `-Infinity` | SQLDataException | REAL negative infinity |
| NaN | `NaN` | NaN | REAL NaN |
| Large finite values | `3.402823E38` / `-3.402823E38` | finite | same finite values |
| Signed zero | `0.0` / `-0.0` | respective signed zero | sign bits preserved |
| SQL NULL | null | getObject null; getFloat 0 with wasNull true | NULL |
| Smallest positive subnormal | `1.401298E-45` | Float.MIN_VALUE | Float.MIN_VALUE |
| Exact stored finite extrema | `3.402823E38` / `-3.402823E38` | finite, reduced precision | same existing wire values |

## Finite precision caveat

For the exact stored positive extremum, widening **on Doris before transport** produced
`3.402823466385289E38`, which converts back to Java Float.MAX_VALUE. The ordinary FLOAT
column text was only `3.402823E38`, which represents a slightly smaller finite value.
The negative extremum behaves symmetrically. This precision loss predates the connector fix.
The tests distinguish the stored value from the ordinary wire value; they do not claim
lossless FLOAT transport or silently invent missing digits.

Constant-expression and stored-column rendering can differ: the initial direct mysql-client
probe of `CAST('3.4028234663852886e38' AS FLOAT)` printed `3.402824e+38`, whereas the stored
column printed `3.402823e+38`. Regression expectations use the actual stored-column path.

## Fix and coverage

- FLOAT now uses getString for both NULL detection and value decoding, like the existing
  DOUBLE mapping. Float parsing accepts IEEE special values and preserves signed zero.
- The decoded value is encoded as Trino REAL bits. Unparseable text throws a descriptive
  TrinoException with the NumberFormatException cause; it is never silently converted to NULL.
- Approximate-type domain pushdown remains disabled.
- The connector scan failed before the fix and passes afterward. Live assertions cover
  mixed special/finite/NULL rows, raw JDBC failures, finite-extrema precision, and local
  infinity/NaN/zero predicates. A strict unit ResultSet rejects any getter other than
  getString/wasNull and tests malformed text.

Verification via `mise exec -- ./gradlew`: 76 tests passed across FLOAT special values,
type mappings, smoke, arrays, aggregates and statistics. Detekt and plugin assembly
verification passed. The full repository suite was not rerun for this focused change.
Doris FE and BE were stopped afterward to release node resources.
