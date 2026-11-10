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

    fun parse(text: String): Slice =
        parseOrNull(text) ?: throw TrinoException(GENERIC_INTERNAL_ERROR, "Unreadable Doris IPV4/IPV6 wire value: '$text'")

    fun parseOrNull(text: String): Slice? {
        val address = try {
            InetAddresses.forString(text)
        } catch (ignored: IllegalArgumentException) {
            return null
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
     * Doris IPV4 and IPV6 reject each other's text forms, so the literal follows the column type.
     * IPV6 is built from the bytes because Guava prints IPv4-mapped addresses in dotted form,
     * which Doris IPV6 rejects.
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

    /** Binds the address as text, which Doris converts to the column's IP type. */
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
