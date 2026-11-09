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

import com.google.common.net.InetAddresses
import io.airlift.slice.Slice
import io.airlift.slice.Slices.wrappedBuffer
import io.trino.plugin.jdbc.SliceWriteFunction
import io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR
import io.trino.spi.TrinoException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.sql.PreparedStatement

/** Conversions between Doris IPV4/IPV6 text and Trino's 16-byte IPADDRESS representation. */
internal object DorisIpAddress {
    private const val BYTES = 16

    /** Canonical Doris IPV4/IPV6 text -> Trino IPADDRESS slice; unparseable text fails loud. */
    fun parse(text: String): Slice {
        val address = try {
            InetAddresses.forString(text)
        } catch (e: IllegalArgumentException) {
            throw TrinoException(GENERIC_INTERNAL_ERROR, "Unreadable Doris IPV4/IPV6 wire value: '$text'", e)
        }
        return wrappedBuffer(*toTrinoBytes(address))
    }

    /** True when a 16-byte Trino IPADDRESS slice is IPv4-mapped (`::ffff:a.b.c.d`). */
    fun isV4Mapped(slice: Slice): Boolean {
        if (slice.length() != BYTES) {
            return false
        }
        for (i in 0 until 10) {
            if (slice.getByte(i).toInt() != 0) {
                return false
            }
        }
        return slice.getByte(10) == 0xFF.toByte() && slice.getByte(11) == 0xFF.toByte()
    }

    /**
     * Renders a Trino IPADDRESS (16-byte big-endian slice) into the TARGET Doris dialect:
     * IPV4 -> dotted-quad of the last 4 bytes (caller guarantees v4-mapped via
     * [DorisPushdownControllers.IPV4_PUSHDOWN] / the array-rule guard); IPV6 -> fully-expanded
     * 8-group lowercase colon-hex (built from bytes, never via Guava, which collapses mapped
     * addresses to a dotted form IPV6 rejects).
     */
    fun renderLiteral(slice: Slice, isV4: Boolean): String {
        val bytes = slice.getBytes()
        require(bytes.size == BYTES) { "IPADDRESS slice must be 16 bytes, was ${bytes.size}" }
        if (isV4) {
            require(isV4Mapped(slice)) { "non-IPv4-mapped IPADDRESS cannot render as a Doris IPV4 literal" }
            return "${bytes[12].toInt() and 0xFF}.${bytes[13].toInt() and 0xFF}." +
                "${bytes[14].toInt() and 0xFF}.${bytes[15].toInt() and 0xFF}"
        }
        return (0 until 8).joinToString(":") { group ->
            val hi = bytes[group * 2].toInt() and 0xFF
            val lo = bytes[group * 2 + 1].toInt() and 0xFF
            Integer.toHexString((hi shl 8) or lo)
        }
    }

    /** Binds an IPADDRESS parameter as its target-dialect text ([renderLiteral]); Doris coerces. */
    fun writeFunction(isV4: Boolean): SliceWriteFunction =
        SliceWriteFunction { statement: PreparedStatement, index: Int, value: Slice ->
            statement.setString(index, renderLiteral(value, isV4))
        }

    /** Trino IPADDRESS representation: 16 bytes, IPv4 as IPv4-mapped IPv6 (::ffff:a.b.c.d). */
    private fun toTrinoBytes(address: InetAddress): ByteArray {
        return when (address) {
            is Inet6Address -> address.address
            is Inet4Address -> ByteArray(BYTES).also {
                it[10] = 0xFF.toByte()
                it[11] = 0xFF.toByte()
                address.address.copyInto(it, 12)
            }
            else -> throw TrinoException(GENERIC_INTERNAL_ERROR, "Unsupported InetAddress subtype: ${address.javaClass.name}")
        }
    }
}
