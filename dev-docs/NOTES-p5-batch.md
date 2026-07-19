# NOTES — P5 batch: JSON equality, cardinality, GUARDED LIKE-prefix

Live probes 2026-07-19 (Doris 4.1.3-rc02, throwaway probes at `/tmp/opencode/p5-probe`;
repeatable pins in `TestDorisP5Batch`; fixture `p5_batch`).

## 1. json_extract_scalar -> json_unquote(json_extract(...)) — EQUALITY ONLY

Registry: `json_extract_scalar` -> `json_unquote`, verdict IDENTICAL (0.7.0; pinned in
[`DorisPushdownEvidence.JSON_EXTRACT_SCALAR`]). The connector's own truth table (raw JDBC
vs Trino-through-the-connector) found the cells the registry tuple doesn't cover:

| cell | Doris `json_unquote(json_extract(...))` | Trino `json_extract_scalar` | consequence |
|---|---|---|---|
| missing key | SQL NULL | SQL NULL | identical ✓ |
| JSON null at path | **SQL NULL** (unlike MySQL's `'null'` text!) | SQL NULL | identical ✓ — makes `=` safe with no null-shape guard |
| string scalars (unicode escapes, `\u00e9`, surrogate-pair emoji, `\t`, `\"`, empty string, `"null"`, `"[1, 2]"`) | unquoted text | same text | identical ✓ |
| boolean | `true`/`false` | same | identical ✓ (pushable literals) |
| **non-scalar at path** (object/array) | the JSON TEXT (`'{"b":1}'`, `'[1,2]'`) | **SQL NULL** | DIVERGENT, data-dependent ⇒ `<>` / `IS NULL` / `IS NOT NULL` NEVER push; `=` guards literal prefix `{`/`[` |
| **numbers** | Doris-canonical: `1e+30`, `1.5e-07`, `-0` | Trino-canonical: `1E+30`, `1.5E-7`, `0` | DIVERGENT rendering ⇒ numeric-looking literals NEVER push (broad regex) |
| int-ish numbers | `1`, `1.1`, `100`, big ints verbatim | same | identical in every probed cell — but guarded anyway by the numeric-literal skip (formatting risk isn't worth the cell-by-cell carve-out) |
| paths | `$.a`, `$.a.b`, `$.a[0]` identical; dotted keys need `$."a.b"` | dotted keys need `$["a.b"]` | path guard: only `$(\.simple_key|\[\d+\])+` pushes |

Notes:
- Doris canonicalizes JSON numbers AT STORAGE (`1.0` -> `1`, `1e2` -> `100`), and Trino
  parses what Doris renders — so most number cells agree; the divergence is in exponent/
  negative-zero RENDERING (`e+30` vs `E+30`, `-0` vs `0`). The guard is a broad
  numeric-looking regex on the literal: any match stays local (correct, just not pushed).
- Predicate-level tier (like `contains`): a remote `NOT` would flip the both-drop divergent
  cells, so the rule is structurally excluded from the value-safe composition tier.
- VARIANT columns are excluded (json only) until separately proven.
- `IS NOT NULL` existence checks: NOT pushable (the task's "consider it" answer is NO —
  non-scalar cells are non-NULL on Doris, NULL on Trino).

## 2. cardinality -> array_size — VALUE-SAFE (composable)

Registry: `cardinality` -> `array_size`, verdict DIVERGENT ("rename required" — Doris has
no array `cardinality`; also covers Trino map-cardinality which cannot reach us: no native
MAP columns exist). G3 connector-original-rewrite lane, pinned in
[`DorisPushdownEvidence.CARDINALITY`].

Truth table (live, both engines): NULL array -> NULL ✓, empty -> 0 ✓, NULL elements
COUNTED (`[1,null,3]` -> 3) ✓, NOT-composition cell-identical ✓ — value-identical on every
reachable cell ⇒ sits in the VALUE-SAFE tier (NOT/AND/OR composable) beside
`array_position`. All six comparison operators + `BETWEEN` + flipped orientation render
inline-Long bounds.

Restrictions:
- Column guard reuses the pushable-array allowlist ⇒ `cardinality` over ARRAY<DOUBLE>/
  ARRAY<IP> stays local — an over-restriction (size never touches elements) accepted to
  keep one proof surface; widen with its own differential if it ever matters.
- `x = 3 OR x = 1` over the same call folds to `$in` upstream — no `$in` rule exists, the
  shape stays local (correct). Mixed-operator ORs compose and push.
- Upstream canonicalization: `NOT(=)` -> `<>`, `NOT(a OR b)` -> De Morgan conjuncts — the
  literal `$not` path is exercised by the P3 pins.

## 3. GUARDED LIKE-prefix range pre-filter — ZERO new connector code

The planned "prefix extraction + next-prefix computation" already exists in Trino core:
the planner derives a prefix RANGE DOMAIN from `col LIKE 'foo%'` (any literal prefix before
the first wildcard) and keeps the LIKE as a filter. In GUARDED mode our P4 varchar
controller ships that domain as a superset pre-filter WITH the exact Trino filter retained:

- `v LIKE 'foo%'` -> remote `WHERE (`v` >= 'foo' AND `v` < 'fop')`, LIKE evaluated locally.
- `v LIKE 'foÿ%'` (U+00FF tail) -> `(`v` >= 'foÿ' AND `v` < 'fp')` — the engine widens the
  successor at the codepoint level; a superset, made exact by the retained LIKE. No 0xFF
  byte-tail handling needed on our side.
- `v LIKE 'fo%X'` (mid-string wildcard) -> prefix range `[fo, fp)` still pre-filters.
- NUL in the prefix -> the P4 GUARDED 0x00 domain scan keeps everything local.
- NULL_ONLY: nothing pushes (mode contract). BINARY/FULL: the P4 LIKE rule wins and the
  whole LIKE goes remote — the two mechanisms compose by construction (rule beats domain
  pre-filter; both are correct).

So item 3 shipped as tests + docs only (`testGuardedLikePrefix*` pins) — the honest
implementation was recognizing the engine already provides it.

## Test surface

`TestDorisP5Batch` (13): JSON equality pushdown + exact remote shape + per-cell
differentials (incl. guarded-out literals — local == pushed truth for all), semantics pins,
the number-canonicalization divergence evidence itself, hazard-shape negatives (`<>`,
IS-NULL forms, `{`/`[`/numeric/NUL literals, quoted-key path); cardinality: 6 operators +
BETWEEN + flips pushed with expected rows, semantics pins (NULL/empty/NULL-elements) vs the
Doris oracle, value-safe composition (AND/OR wire shapes) + guards (ARRAY<DOUBLE>,
non-constant bound); LIKE-prefix: GUARDED range+retained shape, successor edge, NUL policy,
NULL_ONLY/FULL mode interplay. Drift suite now pins 5 evidence tuples.
