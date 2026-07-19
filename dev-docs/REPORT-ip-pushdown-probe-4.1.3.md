# REPORT — IPADDRESS pushdown probe (Doris 4.1.3)

Probed 2026-07-19 against the pinned baseline (Apache Doris `4.1.3-rc02-7126cf65d96`,
Trino 483, Connector/J 9.7.0) to close the standing P2b open item "IPADDRESS parameter
rendering" and unlock IP `=`/IN/range domains, `contains`, `arrays_overlap`, and TopN.

## The structural mismatch

Trino `IPADDRESS` is a SINGLE 16-byte type: every value is stored IPv4-mapped
(`::ffff:a.b.c.d` for IPv4). Doris has TWO distinct types — `IPV4` (4 bytes) and `IPV6`
(16 bytes) — that reject each other's textual forms. So a Trino IPADDRESS cannot be rendered
blindly; the connector must render in the TARGET column's dialect, keyed off the Doris
`COLUMN_TYPE` (`ipv4` / `ipv6`), which survives on the `JdbcTypeHandle`.

## Findings (all live-probed)

1. **Ordering is byte-identical.** Trino `IpAddressType` compares as unsigned big-endian 16
   bytes (`comparisonOperator` → `compareBigEndian`). Doris `ORDER BY` on both IPV4 and IPV6
   orders by the same unsigned byte value. Observed IPV6 order: `::` < `::ffff:192.168.1.1`
   < `2001:db8::…` < `ffff::`; IPV4: `0.0.0.0` < `10.0.0.5` < `192.168.1.1` <
   `255.255.255.255`. For an IPV4 column all values share the `::ffff:` prefix, so the
   4-byte order equals the 16-byte order → TopN is exact for both types. Native
   `NULLS FIRST/LAST` supported.

2. **IPV4 wants dotted-quad; IPV6 rejects it.** `v6 = '192.168.1.1'` → no match (even against
   a v4-mapped stored value); `v6 = '::ffff:192.168.1.1'` → matches. IPV4 comparisons need
   `a.b.c.d`. IPV6 accepts every valid colon-hex form (compressed, fully-expanded, uppercase,
   `::ffff:` mapped). The connector renders IPV6 as the **fully-expanded 8-group lowercase
   colon-hex** built directly from the 16 bytes (never via Guava `toAddrString`, which
   collapses mapped addresses to a dotted quad that IPV6 rejects). IPV4 renders the dotted
   quad of the last 4 bytes.

3. **Cross-form is unsafe, so IPV4 pushes only v4-mapped values.** `array_contains(a4,
   '::ffff:192.168.1.1')` returned a WRONG match (a row whose array does not contain that
   address) — passing a v6-form string into an IPV4 context yields garbage, not a clean
   no-match. Therefore the IPV4 predicate controller pushes a domain ONLY when every value is
   IPv4-mapped; a real IPv6 literal (which can never equal an IPV4 value) keeps the domain
   local — correct, just not pushed. Array-rule needles/elements get the same guard.

4. **`=` and `array_contains` coerce a string needle; `arrays_overlap` does NOT.**
   `v4 = '10.0.0.5'`, `v4 IN (…)`, and `array_contains(a6, '<canonical>')` coerce the bound
   string to the column type and compare correctly. But `arrays_overlap(array<ipv6>,
   ARRAY('<text>'))` compares the varchar array element-wise as TEXT (no coercion), so it
   fails unless the rendered text equals Doris's canonical form. Fix: the constant-array
   `arrays_overlap` rendering casts each element — `ARRAY(CAST(? AS IPV4), …)` /
   `ARRAY(CAST(? AS IPV6), …)` — live-proven to match. (Numeric/date/datetime constant arrays
   need no cast: those parameters bind as their native JDBC type, so Doris infers the element
   type; only IP binds as a string.)

5. **`array<ipv4>` and `array<ipv6>` are distinct.** Trino erases the v4/v6 distinction, so
   `arrays_overlap(a4, a6)` type-checks in Trino; the column×column rule additionally gates on
   the source `ipV4` flag so the two are never overlapped remotely.

## Implementation

- `DorisTypeMapping.ipAddressMapping(isV4)`: type-aware write function
  ([`renderIpLiteral`]) + controller (`IPV4_PUSHDOWN` gates on v4-mapped; IPV6 = `FULL_PUSHDOWN`).
- `DorisArrayElement.IpAddressElement(ipAddressType, isV4)` carries the dialect; the array
  rules guard IP needles/elements and cast constant-array IP elements.
- `DorisClient.isPushableSortKey` allows IPADDRESS sort keys.

## Live proof

`TestDorisIpPushdown` (8 cases): v4/v6 domain (`=`/IN/range/IS NULL) differentials, the
non-v4-mapped-stays-local guard, `contains`, `arrays_overlap` (column×column, column×constant,
flipped), TopN byte-exactness vs the pushdown-disabled session, and remote-SQL audit-log
shapes.
