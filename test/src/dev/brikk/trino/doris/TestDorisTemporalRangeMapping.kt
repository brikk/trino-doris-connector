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

import io.airlift.slice.Slices.utf8Slice
import io.trino.plugin.jdbc.JdbcMetadataConfig
import io.trino.plugin.jdbc.JdbcMetadataSessionProperties
import io.trino.spi.predicate.Domain
import io.trino.spi.predicate.Range
import io.trino.spi.predicate.ValueSet
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.testing.TestingConnectorSession
import io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Optional
import java.util.concurrent.TimeUnit.DAYS

class TestDorisTemporalRangeMapping {
    private val mapping = DorisTypeMapping(TESTING_TYPE_MANAGER)
    private val session = TestingConnectorSession.builder()
        .setPropertyMetadata(JdbcMetadataSessionProperties(JdbcMetadataConfig(), Optional.empty()).sessionProperties)
        .setPropertyValues(mapOf("domain_compaction_threshold" to 2))
        .build()
    private val minDate = LocalDate.of(0, 1, 1).toEpochDay()
    private val maxDate = LocalDate.of(9999, 12, 31).toEpochDay()
    private val cases = listOf(Triple("date", minDate, maxDate)) + listOf(0, 3, 6).map {
        Triple("datetime($it)", DAYS.toMicros(minDate), DAYS.toMicros(LocalDate.of(10000, 1, 1).toEpochDay()) - step("datetime($it)"))
    }

    @Test
    fun testTemporalBounds() {
        for ((columnType, low, high) in cases) {
            val type = mapping.toColumnMapping(session, columnType).orElseThrow().type
            val step = step(columnType)
            for (value in listOf(low, low + step, 0L, high - step, high)) {
                assertThat(DorisTypeMapping.isWithinTemporalRange(type, value)).describedAs("$columnType: $value").isTrue()
            }
            for (value in listOf(low - step, high + step, Long.MIN_VALUE, Long.MAX_VALUE)) {
                assertThat(DorisTypeMapping.isWithinTemporalRange(type, value)).describedAs("$columnType: $value").isFalse()
            }
        }
    }

    @Test
    fun testMappedDomains() {
        for ((columnType, low, high) in cases) {
            val type = mapping.toColumnMapping(session, columnType).orElseThrow().type
            val step = step(columnType)
            for (domain in listOf(Domain.onlyNull(type), Domain.notNull(type), Domain.all(type), Domain.none(type))) {
                assertPushdown(columnType, domain)
            }
            for (nullAllowed in listOf(false, true)) {
                for (domain in listOf(
                    Domain.singleValue(type, low, nullAllowed),
                    Domain.singleValue(type, high, nullAllowed),
                    Domain.multipleValues(type, listOf(low, high), nullAllowed),
                    Domain.create(ValueSet.ofRanges(Range.range(type, low, true, high, true)), nullAllowed),
                    Domain.create(ValueSet.ofRanges(Range.greaterThanOrEqual(type, low)), nullAllowed),
                    Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(type, high)), nullAllowed),
                )) {
                    assertPushdown(columnType, domain)
                }
                for (bad in listOf(low - step, high + step)) {
                    for (domain in listOf(
                        Domain.singleValue(type, bad, nullAllowed),
                        Domain.multipleValues(type, listOf(0L, bad), nullAllowed),
                        Domain.create(ValueSet.ofRanges(Range.range(type, minOf(bad, 0L), true, maxOf(bad, 0L), true)), nullAllowed),
                        Domain.create(ValueSet.ofRanges(Range.lessThan(type, bad)), nullAllowed),
                        Domain.create(ValueSet.ofRanges(Range.greaterThan(type, bad)), nullAllowed),
                    )) {
                        assertPushdown(columnType, domain, Domain.all(type), domain)
                    }
                }
            }
        }
    }

    @Test
    fun testCompactionValidatesOriginalEndpoints() {
        for ((columnType, low, high) in cases) {
            val type = mapping.toColumnMapping(session, columnType).orElseThrow().type
            val step = step(columnType)
            for (nullAllowed in listOf(false, true)) {
                val valid = Domain.multipleValues(type, listOf(low, 0L, high), nullAllowed)
                val compacted = Domain.create(ValueSet.ofRanges(Range.range(type, low, true, high, true)), nullAllowed)
                assertPushdown(columnType, valid, compacted, valid)
                val mixed = Domain.multipleValues(type, listOf(low - step, 0L, high), nullAllowed)
                assertPushdown(columnType, mixed, Domain.all(type), mixed)
                // Compaction erases these invalid inner endpoints, leaving an unbounded span.
                val invalid = Domain.create(ValueSet.ofRanges(
                    Range.lessThan(type, low - 2 * step),
                    Range.range(type, low - step, false, high + step, false),
                    Range.greaterThan(type, high + 2 * step),
                ), nullAllowed)
                assertThat(invalid.simplify(2)).isEqualTo(Domain.create(ValueSet.all(type), nullAllowed))
                assertPushdown(columnType, invalid, Domain.all(type), invalid)
            }
        }
    }

    @Test
    fun testNonTemporalValuesUnaffected() {
        for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE, null)) {
            assertThat(DorisTypeMapping.isWithinTemporalRange(BIGINT, value)).isTrue()
        }
        assertThat(DorisTypeMapping.isWithinTemporalRange(VARCHAR, utf8Slice("10000-01-01"))).isTrue()
        val domain = Domain.multipleValues(BIGINT, listOf(Long.MIN_VALUE, Long.MAX_VALUE), true)
        val result = DorisTypeMapping.TEMPORAL_PUSHDOWN.apply(session, domain)
        assertThat(result.pushedDown).isEqualTo(domain)
        assertThat(result.remainingFilter).isEqualTo(Domain.all(BIGINT))
    }

    private fun assertPushdown(columnType: String, domain: Domain, pushed: Domain = domain, remaining: Domain = Domain.all(domain.type)) {
        val result = mapping.toColumnMapping(session, columnType).orElseThrow().predicatePushdownController.apply(session, domain)
        assertThat(result.pushedDown).describedAs("$columnType pushed: $domain").isEqualTo(pushed)
        assertThat(result.remainingFilter).describedAs("$columnType remaining: $domain").isEqualTo(remaining)
    }

    private fun step(columnType: String): Long = when (columnType) {
        "datetime(0)" -> 1_000_000L
        "datetime(3)" -> 1000L
        else -> 1L
    }
}
