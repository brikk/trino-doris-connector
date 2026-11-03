# P4 string comparison probe — Apache Doris 4.1.3 vs Trino 483 semantics

Evidence for the configurable string-pushdown modes (`doris.string-pushdown.mode` /
`string_pushdown_mode`). Raw output: `evidence/string-probe-raw-output-4.1.3.txt`; probed
live 2026-07-19 (fixture `p4_probe`, parameterized JDBC inserts/lookups so NUL/zero-width/
control characters travel byte-exactly). Trino-side cells are pinned live in
`TestDorisP4StringPushdown`.

## Headline

**Doris 4.1.3 string comparison, IN, BETWEEN, range, LIKE matching, and ORDER BY are pure
BYTE (memcmp) semantics over UTF-8** — `@@collation_connection = utf8mb4_0900_bin`. That is
exactly Trino's VARCHAR semantics (codepoint order == UTF-8 byte order). One undetectable
under-return hazard (CHAR trailing-space data — handled by type exclusion), one
operator-divergence (LIKE default escape — handled by pattern rewrite), and one
once-observed transient miss (NUL-bearing literal — unreproducible; handled in GUARDED by a
defense-in-depth value scan) exist.

## Comparison matrix (column `v VARCHAR`, stored bytes verified via `hex()`)

| shape | Doris result | Trino semantics | classification |
|---|---|---|---|
| `= 'a'` vs stored `A` | no match (case-sensitive) | same | identical |
| `= 'a'` vs stored `a ` / ` a` | no match (no pad/trim) | same | identical |
| `=` NFC `é` vs stored NFD (`e`+U+0301) | no match (no normalization) | same | identical |
| `=` 4-byte emoji, zero-width (U+200B), tab, 190-char strings | exact byte match | same | identical |
| `<`, `>`, `BETWEEN`, `IN` over all of the above | byte order / byte equality | same | identical |
| **`=` a literal containing NUL (U+0000)** | **TRANSIENT**: one probe run returned wrong-empty (during the host-memory-pressure regime that later escalated to BE `MEM_ALLOC_FAILED`); reproductions — same connection string, both fixtures, bound param AND `'a\0b'` literal, plus the connector FULL path — all match `[9]` byte-exactly | matches | **byte-exact on every reproduction; GUARDED still skips 0x00-bearing domains as defense-in-depth (skipping is always correct; a silent wrong-empty was observed once)** |
| **CHAR column, stored value with trailing space** (`c CHAR(10)` holds `a ` = `6120`) | `c = 'a'` misses it (byte compare, no padding; Doris stores CHAR unpadded) | Trino CHAR trims/pads: the read value is `a`, `c = 'a'` MATCHES | **UNDER-RETURN — NOT detectable from the literal (hazard is in stored data) ⇒ CHAR excluded from value pushdown in GUARDED** |
| CHAR + NUL insert | Doris TRUNCATES CHAR at NUL on write (`a\0b` → `61`); VARCHAR/STRING store it byte-exactly (`610062`) | read-only connector reads what is stored | storage note, not a read-path hazard |

## LIKE

| shape | Doris | Trino | classification |
|---|---|---|---|
| case sensitivity | sensitive (`'a%'` ≠ `A…`) | same | identical |
| `_` | one CHARACTER (é=1, emoji=1, NFD=2) | same | identical |
| `%` | any run | same | identical |
| **backslash in pattern, no ESCAPE** | `\` is Doris's DEFAULT escape (`'a\%b'` → literal `%`) | `\` is a LITERAL | **DIVERGENT — exact fix: double backslashes (`\`→`\\`) when rendering (proven: Doris `'a\\b'` matches literal `a\b`)** |
| `LIKE ... ESCAPE 'x'` | supported; ONLY the declared char escapes (backslash becomes literal — `'a\%b' ESCAPE '!'` matched `a\b…` shape) | same contract | identical (escape variant pushable as-is) |
| NUL in pattern | matched byte-exactly in the probe — patterns containing NUL are conservatively not pushed anyway (cheap guard, mirrors the GUARDED domain scan) | — | skip (cheap guard) |
| **no wildcard in pattern** | n/a — never reaches the connector's LIKE rule | Trino folds wildcard-free `LIKE` into an **equality domain** during planning; it ships through the domain path (e.g. `LIKE 'a\b'` → remote `` `v` = 'a\\b' ``) | engine behavior, pinned in the suite |

## ORDER BY (string TopN eligibility)

`ORDER BY v ASC` returned exactly ascending byte order over the adversarial set
(` a` < `A` < `A%B` < `B` < `a` < `a\0b` < `a\tb` < `a ` < `a%b` < … < `a<ZWSP>b` < `b` <
NFD < `x…y` < NFC `é` < emoji) — **identical to Trino's VARCHAR codepoint ordering**.
⇒ VARCHAR/STRING sort keys are TopN-eligible in BINARY/FULL. CHAR sort keys stay OFF in
every mode: Trino orders the trimmed/padded value, Doris orders stored bytes — divergent for
trailing-space data and undetectable from the query.

## Forcing mechanisms (the BINARY-mode question)

- `@@collation_connection = utf8mb4_0900_bin` — the server-side regime IS binary.
- `BINARY 'a'` operator: accepted (`'a' = BINARY 'A'` → 0); `COLLATE utf8mb4_0900_bin`:
  accepted; `CAST(x AS BINARY)`: rejected.
- Comparisons are ALREADY byte-exact, so **no forcing is needed: BINARY shares the FULL
  rendering**. The byte-exactness assumption is verified by this probe and re-verified by
  the suite's semantics pins (a Doris upgrade that adds non-binary collation behavior fails
  the pins loud). BINARY exists as the mode whose CONTRACT is verified-byte-semantics;
  FULL is the caller-asserted mode. On 4.1.3 they render identically.

## Mode consequences (implemented)

| mode | VARCHAR/STRING value domains | CHAR value domains | retained filter | string TopN | LIKE |
|---|---|---|---|---|---|
| NULL_ONLY | not pushed (IS [NOT] NULL only) | same | n/a | off | off |
| GUARDED | pushed as superset pre-filter, **Trino filter RETAINED**; domains containing a 0x00 byte are skipped entirely | NOT pushed (undetectable trailing-space hazard) | yes | off | off |
| BINARY | full pushdown (verified byte semantics, incl. NUL literals) | full (divergence for CHAR trailing-space data documented + tested) | no | VARCHAR keys on | on (backslash-doubling; ESCAPE passthrough) |
| FULL | same rendering as BINARY (caller asserts) | same | no | same | same |

## Default decision

**Default = GUARDED.** Criterion: "GUARDED iff no under-return hazards that detection can't
catch." The undetectable hazard — CHAR trailing-space data — is caught categorically by
excluding CHAR from GUARDED value pushdown; the once-observed transient NUL miss is caught
(beyond need, per reproductions) by the 0x00 domain-value scan. With those in place GUARDED
returns results identical to NULL_ONLY on the full adversarial fixture (differential-tested
per mode) while pre-filtering remotely.

## Reproduce

Throwaway probes at `/tmp/opencode/p4-probe` (`StringProbe.java`, `LikeProbe.java`, and the
NUL reconciliation probes `NulProbe.java`/`NulProbe2.java`); raw output checked in as
evidence. The repeatable pins live in `TestDorisP4StringPushdown`.

### NUL reconciliation (post-probe)

The original `StringProbe` run recorded `param a<NUL>b -> []` against `p4_probe.t`. Every
subsequent attempt to reproduce — identical JDBC URL, both `p4_probe.t` and `p4_strings.t`,
bound parameter and inline `'a\0b'` literal, and Trino FULL-mode end-to-end — returns `[9]`
(stored hex `610062` confirmed each time). The single miss coincided with the host memory
exhaustion that later made the BE fail all queries with `MEM_ALLOC_FAILED`. Verdict: NUL
comparisons are byte-exact; the GUARDED skip is retained because a silent wrong-empty result
was observed once and skipping costs only a local filter.
