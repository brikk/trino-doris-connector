# NOTES — READ-ONLY-MAX batch 1: scalar expression pushdown

Probes 2026-07-19 (`/tmp/opencode/b1-probe`); pins in `TestDorisB1ScalarPushdown`;
evidence in `DorisPushdownEvidence` (ALL grew 7 -> 19).

## Guard widen check (item 0)

The GUARDED string guard has NO character-class restriction to widen: it admits everything
except 0x00 (and CHAR columns). Fresh Latin-1 sweep: all 255 single-byte codepoints
(multi-byte UTF-8 on the wire) round-trip equality EXACTLY (0 mismatches) — backing the P4
byte-exactness evidence with a systematic sweep. Outcome: guard already ≥ Latin-1.

## Machinery

One SCALAR VALUE tier (`DorisScalarPushdownRules`): typed, mutually-composable rules over a
closed value-type set (exact non-text types + VARCHAR; CHAR and REAL/DOUBLE excluded).
The tier is simultaneously:
- the PROJECTION expression rewriter (generic `RewriteScalarProjection` -> synthetic typed
  columns -> composes with GROUP BY + aggregate pushdown), and
- the operand rewriter for the PREDICATE bridges (comparisons / IS NULL over function
  shapes; plain column shapes remain domain territory). Bridges are top-level-conjunct
  tier (not under NOT/AND/OR).

## Per-item verdicts

| item | verdict | key proof |
|---|---|---|
| CASE / IF | **ENGINE-DENIED at 483** — the ConnectorExpression grammar has no conditional form (StandardFunctions carries none); the translator decomposes searched CASE into fragments and folds simple predicate CASEs into comparisons upstream (probed via rejection logs: the connector NEVER receives a conditional). Pinned so a future Trino that translates CASE surfaces as a plan change. Batch-2 note: aggregate ARGUMENTS traverse the same translator — conditional aggregation via CASE args will hit the same wall. | shape probe |
| coalesce / nullif | PUSHED (registry-IDENTICAL re-proven): NULL propagation, nullif(a,a)=NULL, text nullif byte-exact case-SENSITIVE (nullif('a','A')='a' both). Projection + predicate + GROUP BY key + composition (`coalesce(nullif(n,5),-1)`). | live pins |
| year/month/day/hour/minute/second | PUSHED (registry-IDENTICAL re-proven): 0000-01-01 -> 0 / 9999-12-31 -> 9999 edges, fraction truncation identical, all precisions. PREDICATE position comes free upstream (UnwrapYearInComparison -> datetime range domain; probed — the call never reaches the connector; remote SQL shows a range, no function). | probe + differentials |
| lower / upper | PUSHED in all value modes — **the divergence hunt came back EMPTY**: the Unicode special-mapping adversary battery (µ->Μ [BOTH engines apply the special mapping], ß no-expansion, ΑΣ->ασ no-final-sigma, Kelvin->k, Å->å, ſ->S, DZ digraphs, İ->i, roman numerals, fullwidth) maps IDENTICALLY — Doris matches Trino's simple+special (Character-family) mapping on every probed codepoint. Identity claimed on that battery basis, agreement-pinned per adversary on BOTH engines so either engine changing fails loud. NULL_ONLY mode still pushes no string comparison (contract). | agreement battery |
| length | PUSHED as **char_length** (registry length=DIVERGENT: Doris length()=BYTES). Emoji=1, CJK=1/char, NFD combining sequences per-codepoint — identical to Trino character counts. The drift canary's `length` deny GRADUATED exactly per its contract (rule ships WITH live proof); canary keeps element_at. | multibyte pins |
| starts_with | PUSHED as escaped LIKE-prefix `(col LIKE '<esc>%')` — chosen over left(col,n)=prefix for zone-map/index eligibility; metacharacters escaped (%->\%, _->\_, \->\\; live-proven literal matching), NULL->NULL, empty prefix = all non-NULL, 0x00 prefixes local, CHAR columns excluded, GUARDED+ modes (NULL_ONLY local). | probe + row pins |

## Remote SQL shapes

- `coalesce(nullif(`n`, ?), ?)` (params bound), `(nullif(`n`, ?) IS NULL)`
- `SELECT year(`dt6`) AS _pfgnrtd_0 ... GROUP BY _pfgnrtd_0` (one statement with aggregates)
- `(lower(`v`) = ?)`, `char_length(`v`)`, `(`v` LIKE 'a\%b%')`
