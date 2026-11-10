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
import io.trino.spi.type.Decimals.MAX_PRECISION
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.RealType.REAL
import io.trino.spi.type.SmallintType.SMALLINT
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TinyintType.TINYINT
import io.trino.spi.type.Type

/**
 * ARRAY element types whose Doris text rendering decodes unambiguously. String elements are
 * excluded: Doris quotes them without escaping, so the one element `a", "b` and the two
 * elements `a`, `b` both arrive as `["a", "b"]`. Arrays of other element types follow the
 * unsupported-type policy in [DorisTypeMapping].
 */
internal sealed class DorisArrayElement {
    abstract val trinoType: Type

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

    /** LARGEINT spans ±(2^127 - 1), beyond DECIMAL(38,0); values out of range fail rather than clamp. */
    object LargeintElement : DorisArrayElement() {
        override val trinoType: Type = createDecimalType(MAX_PRECISION, 0)
    }

    data class DecimalElement(val decimalType: DecimalType) : DorisArrayElement() {
        override val trinoType: Type get() = decimalType
    }

    object RealElement : DorisArrayElement() {
        override val trinoType: Type = REAL
    }

    object DoubleElement : DorisArrayElement() {
        override val trinoType: Type = DOUBLE
    }

    object DateElement : DorisArrayElement() {
        override val trinoType: Type = DATE
    }

    data class TimestampElement(val timestampType: TimestampType) : DorisArrayElement() {
        override val trinoType: Type get() = timestampType
    }

    /** [isV4] keeps the Doris element type, which Trino's IPADDRESS erases, for rendering literals. */
    data class IpAddressElement(val ipAddressType: Type, val isV4: Boolean) : DorisArrayElement() {
        override val trinoType: Type get() = ipAddressType
    }

    data class NestedArrayElement(val element: DorisArrayElement) : DorisArrayElement() {
        val arrayType: ArrayType = ArrayType(element.trinoType)
        override val trinoType: Type get() = arrayType
    }
}
