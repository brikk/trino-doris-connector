/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.trino.doris

import io.trino.spi.type.ArrayType
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.DecimalType
import io.trino.spi.type.DecimalType.createDecimalType
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.RealType.REAL
import io.trino.spi.type.SmallintType.SMALLINT
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TinyintType.TINYINT
import io.trino.spi.type.Type

/**
 * The v1 native ARRAY element allowlist, exactly per the ledger §A "ARRAY element allowlist
 * verdict" (GO-WITH-RESTRICTIONS, ARRAY spike §1/§4): only element types whose Doris wire
 * rendering is provably unambiguous. There is deliberately NO string-family member —
 * ARRAY<VARCHAR/CHAR/STRING> is a hard NO-GO (spike F4): Doris emits quoted string elements
 * with ZERO escaping, so two semantically different arrays can be byte-identical on the wire
 * (`p0_array_probe0.amb`: one element `a", "b` vs two elements `a`,`b` both serialize to
 * `["a", "b"]`); decoding would be "sometimes silently wrong", which AGENTS.md disqualifies.
 * `ARRAY<JSON>` is not creatable on 4.1.3 (spike F5); MAP/STRUCT/VARIANT leaves are separate
 * wire work. Denied-leaf arrays follow the unsupported-type policy in [DorisTypeMapping].
 */
internal sealed class DorisArrayElement {
    abstract val trinoType: Type

    /** Doris BOOLEAN (`tinyint(1)` in COLUMN_TYPE); wire tokens are bare `1`/`0`, never true/false. */
    object BooleanElement : DorisArrayElement() {
        override val trinoType: Type = BOOLEAN
    }

    object TinyintElement : DorisArrayElement() {
        override val trinoType: Type = TINYINT
    }

    object SmallintElement : DorisArrayElement() {
        override val trinoType: Type = SMALLINT
    }

    object IntegerElement : DorisArrayElement() {
        override val trinoType: Type = INTEGER
    }

    object BigintElement : DorisArrayElement() {
        override val trinoType: Type = BIGINT
    }

    /**
     * LARGEINT (signed 128-bit) -> DECIMAL(38,0), decoded via BigInteger and FAILING LOUD out
     * of DECIMAL(38,0) range — the ±(2^127−1) extremes round-trip through BigInteger but
     * exceed DECIMAL(38,0); never clamp (ledger §A; spike §7.4).
     */
    object LargeintElement : DorisArrayElement() {
        override val trinoType: Type = createDecimalType(38, 0)
    }

    /** DECIMAL(p<=38, s): wire preserves trailing zeros/scale; decode with BigDecimal, no double round-trip (spike F2). */
    data class DecimalElement(val decimalType: DecimalType) : DorisArrayElement() {
        override val trinoType: Type get() = decimalType
    }

    /** FLOAT: value-exact on the wire (spike F2/F3). Approximate type — never pushdown-eligible. */
    object RealElement : DorisArrayElement() {
        override val trinoType: Type = REAL
    }

    /**
     * DOUBLE: GO with the documented boundary caveat (spike F3): Doris renders DOUBLE max as
     * `1.797693134862316e+308` (16 sig digits) which reparses to Infinity in Java. Surfaced
     * faithfully as Infinity/-Infinity/NaN — never papered over with a silently-wrong finite
     * value, never a decode error (ledger §A "FLOAT/DOUBLE array caveat").
     */
    object DoubleElement : DorisArrayElement() {
        override val trinoType: Type = DOUBLE
    }

    object DateElement : DorisArrayElement() {
        override val trinoType: Type = DATE
    }

    /** DATETIME(0..6) -> TIMESTAMP(p); wire fraction width equals the declared scale, microseconds preserved. */
    data class TimestampElement(val timestampType: TimestampType) : DorisArrayElement() {
        override val trinoType: Type get() = timestampType
    }

    /**
     * IPV4/IPV6 -> IPADDRESS: quoted canonical text whose alphabet excludes all structural chars
     * (spike §4). [isV4] preserves the Doris source type (erased by Trino's single 16-byte
     * IPADDRESS) so pushdown can render the correct dialect literal ([DorisTypeMapping.renderIpLiteral]).
     */
    data class IpAddressElement(val ipAddressType: Type, val isV4: Boolean) : DorisArrayElement() {
        override val trinoType: Type get() = ipAddressType
    }

    /** Nested ARRAY of an allowlisted element (≥3 levels proven, spike F2); denied leaves inherit the denial. */
    data class NestedArrayElement(val element: DorisArrayElement) : DorisArrayElement() {
        val arrayType: ArrayType = ArrayType(element.trinoType)
        override val trinoType: Type get() = arrayType
    }
}
