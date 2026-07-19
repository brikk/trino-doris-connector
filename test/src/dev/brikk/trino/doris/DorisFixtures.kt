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

import io.airlift.log.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-provisioning base fixtures: the `p0_probe` database every live suite assumes exists.
 *
 * Historically `p0_probe` was created MANUALLY by the P0 probe harness on the dev cluster and
 * never by test code — the first CI run (bare runner, fresh cluster) failed en masse because
 * of it. This bootstrapper replicates the original fixtures EXACTLY (proven by two-way
 * `EXCEPT` diffs of every table against the original dev-cluster data before it was ever
 * wiped — see dev-docs/NOTES-ci-fixture-bootstrap.md):
 *
 * - `scalars` (8 rows incl. the duplicate id=6 rows): LARGEINT extremes, DOUBLE_MAX rows
 *   (the wire renders them as Infinity — the ledger's getString READ rule), one real
 *   Infinity/NaN row, -0.0 rows, DECIMAL(76,10) 65-nines extremes (needs
 *   `enable_decimal256`), zero-dates `0000-01-01`, empty-vs-NULL strings, JSON null,
 *   unicode/escape adversaries, IPv4/IPv6 extremes, VARIANT shapes.
 * - `arrays` (4 rows): every element family incl. NULL elements, nested arrays, wire-format
 *   adversaries (embedded quotes/commas/brackets in string elements).
 * - `mapstruct` (4 rows), `opaque` (2 rows: BITMAP/HLL/AGG_STATE — needs
 *   `enable_agg_state`), `scalars_view`, and `nums` (0..1000999, 1_001_000 distinct rows,
 *   seeded in ~250ms via the `numbers()` TVF).
 *
 * Idempotent: a fast row-count fingerprint check skips provisioning when the fixtures are
 * already present and complete (dev-cluster runs); any mismatch (fresh CI cluster, partial
 * state from a crashed run) drops and rebuilds the database. Session-variable statements
 * (`enable_decimal256`, `enable_agg_state`) share one connection with the DDL by
 * [DorisTestCluster.executeAsRoot]'s contract.
 *
 * Wired as the first step of [DorisQueryRunner.Builder.build] (every live suite constructs a
 * runner) AND callable directly by suites whose OWN provisioning reads `p0_probe` before the
 * runner exists (cancellation/inverted-index/TopN build derived tables from `nums`).
 */
object DorisFixtures {
    private val log = Logger.get(DorisFixtures::class.java)
    private val provisioned = AtomicBoolean(false)

    private val EXPECTED_COUNTS = mapOf(
        "scalars" to 8L,
        "arrays" to 4L,
        "mapstruct" to 4L,
        "opaque" to 2L,
        "nums" to 1_001_000L,
        "scalars_view" to 8L,
    )

    /** Idempotent, JVM-once fast path; safe to call from any suite setup. */
    @JvmStatic
    fun ensureBaseFixtures() {
        if (provisioned.get()) {
            return
        }
        synchronized(this) {
            if (provisioned.get()) {
                return
            }
            if (fixturesComplete()) {
                log.info("p0_probe fixtures already complete — skipping provisioning")
            } else {
                log.info("p0_probe fixtures missing or incomplete — provisioning from scratch")
                val startMillis = System.currentTimeMillis()
                DorisTestCluster.executeAsRoot(*PROVISION_STATEMENTS)
                check(fixturesComplete()) { "p0_probe provisioning finished but the fingerprint check failed" }
                log.info("p0_probe provisioned in %sms", System.currentTimeMillis() - startMillis)
            }
            provisioned.set(true)
        }
    }

    private fun fixturesComplete(): Boolean {
        val schema = DorisTestCluster.queryScalar(
            "SELECT SCHEMA_NAME FROM information_schema.schemata WHERE SCHEMA_NAME = 'p0_probe'",
        ) ?: return false
        check(schema == "p0_probe")
        return EXPECTED_COUNTS.all { (table, expected) ->
            runCatching { DorisTestCluster.queryScalar("SELECT COUNT(*) FROM p0_probe.`$table`") }
                .getOrNull()?.toLongOrNull() == expected
        }
    }

    /**
     * The exact replica of the original manually-provisioned fixtures. Every statement was
     * executed against a scratch database and two-way EXCEPT-diffed column-by-column against
     * the original `p0_probe` (0 differing rows on all tables) before the original was
     * retired. Do not edit values without re-running such a diff against a cluster that
     * still carries proven-good fixtures.
     */
    @Suppress("MaxLineLength", "ktlint:standard:max-line-length")
    private val PROVISION_STATEMENTS = arrayOf(
        "DROP DATABASE IF EXISTS p0_probe",
        "CREATE DATABASE p0_probe",
        """SET enable_decimal256 = true""",
        """SET enable_agg_state = true""",
        """CREATE TABLE p0_probe.scalars (id INT NULL, c_boolean BOOLEAN NULL, c_tinyint TINYINT NULL, c_smallint SMALLINT NULL, c_int INT NULL, c_bigint BIGINT NULL, c_largeint LARGEINT NULL, c_float FLOAT NULL, c_double DOUBLE NULL, c_dec9_2 DECIMAL(9,2) NULL, c_dec38_10 DECIMAL(38,10) NULL, c_dec76_10 DECIMAL(76,10) NULL, c_date DATE NULL, c_dt0 DATETIME NULL, c_dt3 DATETIME(3) NULL, c_dt6 DATETIME(6) NULL, c_char10 CHAR(10) NULL, c_varchar100 VARCHAR(100) NULL, c_string STRING NULL, c_json JSON NULL, c_ipv4 IPV4 NULL, c_ipv6 IPV6 NULL, c_variant VARIANT NULL) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')""",
        """INSERT INTO p0_probe.scalars VALUES (1, true, 42, 1234, 100000, 9000000000000000000, 170141183460469231731687303715884105727, 3.14, 2.718281828459045, 1234567.89, 12345678901234567890.1234567890, 123456789012345678901234567890.1234567890, '2021-06-15', '2021-06-15 12:34:56', '2021-06-15 12:34:56.789', '2021-06-15 12:34:56.789012', 'char10', 'a normal varchar', 'a normal string', '{"k":"v","n":42,"arr":[1,2,3]}', '192.168.1.1', '2001:db8::ff00:42:8329', '{"vk":"vv","vn":7}')""",
        """INSERT INTO p0_probe.scalars VALUES (2, false, -128, -32768, -2147483648, -9223372036854775808, -170141183460469231731687303715884105727, -3.402823e38, -1.7976931348623157e308, -9999999.99, -9999999999999999999999999999.9999999999, -99999999999999999999999999999999999999999999999999999999999999999.9999999999, '0000-01-01', '0000-01-01 00:00:00', '0000-01-01 00:00:00', '0000-01-01 00:00:00', '', '', '', 'null', '0.0.0.0', '::', '{}')""",
        """INSERT INTO p0_probe.scalars VALUES (3, true, 127, 32767, 2147483647, 9223372036854775807, 170141183460469231731687303715884105727, 3.402823e38, 1.7976931348623157e308, 9999999.99, 9999999999999999999999999999.9999999999, 99999999999999999999999999999999999999999999999999999999999999999.9999999999, '9999-12-31', '9999-12-31 23:59:59', '9999-12-31 23:59:59.999', '9999-12-31 23:59:59.999999', 'ABCDEFGHIJ', 'xyz', 'zzz', '[1,2,3]', '255.255.255.255', 'ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff', '[true,null,"x"]')""",
        """INSERT INTO p0_probe.scalars VALUES (4, true, 0, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '2000-02-29', NULL, NULL, '2000-02-29 01:02:03.000001', 'ab', 'comma,\'single\'"double"[br]\\bs', 'emoji 😀 CJK 中文 tab	tab nl
nl', '{"unicode":"中文 😀","q":"he said \\"hi\\"","path":"a\\\\b"}', '10.0.0.255', 'fe80::1', '{"deep":{"a":[1,{"b":"中文"}]}}')""",
        """INSERT INTO p0_probe.scalars (id) VALUES (5)""",
        """INSERT INTO p0_probe.scalars (id, c_float, c_double) VALUES (6, cast('nan' AS FLOAT), cast('Infinity' AS DOUBLE)), (6, cast('-0.0' AS FLOAT), cast('-0.0' AS DOUBLE)), (6, cast('-0.0' AS FLOAT), cast('-0.0' AS DOUBLE))""",
        """CREATE VIEW p0_probe.scalars_view (id, c_int, c_varchar100) AS SELECT id, c_int, c_varchar100 FROM p0_probe.scalars""",
        """CREATE TABLE p0_probe.arrays (id INT NULL, a_int ARRAY<INT> NULL, a_bigint ARRAY<BIGINT> NULL, a_largeint ARRAY<LARGEINT> NULL, a_float ARRAY<FLOAT> NULL, a_double ARRAY<DOUBLE> NULL, a_dec9_2 ARRAY<DECIMAL(9,2)> NULL, a_date ARRAY<DATE> NULL, a_dt6 ARRAY<DATETIME(6)> NULL, a_boolean ARRAY<BOOLEAN> NULL, a_varchar50 ARRAY<VARCHAR(50)> NULL, a_string ARRAY<STRING> NULL, a_array_int ARRAY<ARRAY<INT>> NULL) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')""",
        """INSERT INTO p0_probe.arrays SELECT 1, array(1,2,3), array(10,20,30), array(cast(170141183460469231731687303715884105727 AS LARGEINT), cast(-1 AS LARGEINT)), array(1.5,2.5), array(1.25,2.5), array(cast(1.10 AS DECIMAL(9,2)), cast(2.20 AS DECIMAL(9,2))), array(cast('2021-01-01' AS DATE), cast('2021-12-31' AS DATE)), array(cast('2021-06-15 12:34:56.789012' AS DATETIME(6)), cast('2000-01-01 00:00:00' AS DATETIME(6))), array(true,false,true), array('a','b','c'), array('s1','s2'), array(array(1,2), array(3,4))""",
        """INSERT INTO p0_probe.arrays SELECT 2, array(), array(), array(), array(), array(), array(), array(), array(), array(), array(), array(), array()""",
        """INSERT INTO p0_probe.arrays (id) VALUES (3)""",
        """INSERT INTO p0_probe.arrays SELECT 4, array(1,NULL,3), array(NULL,20), array(cast(NULL AS LARGEINT)), array(NULL,1.5), array(NULL,2.5), array(NULL, cast(1.10 AS DECIMAL(9,2))), array(NULL, cast('2021-01-01' AS DATE)), array(NULL, cast('2021-06-15 12:34:56.789012' AS DATETIME(6))), array(NULL,true), array(NULL,'has,comma','has\'quote','has"dq','has[br]','has\\bs'), array(NULL,'emoji 😀','CJK 中文','tab	here','nl here'), array(array(1,NULL), NULL, array())""",
        """CREATE TABLE p0_probe.mapstruct (id INT NULL, m_si MAP<STRING,INT> NULL, s_ab STRUCT<a:INT, b:STRING> NULL) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')""",
        """INSERT INTO p0_probe.mapstruct SELECT 1, map('a',1,'b',2), named_struct('a',10,'b','hello')""",
        """INSERT INTO p0_probe.mapstruct SELECT 2, map(), named_struct('a',NULL,'b',NULL)""",
        """INSERT INTO p0_probe.mapstruct (id) VALUES (3)""",
        """INSERT INTO p0_probe.mapstruct SELECT 4, map('k,1',1,'q\'ok',2,'uni 中文',3), named_struct('a',-1,'b','comma,\'q\'"dq"\\bs 中文')""",
        """CREATE TABLE p0_probe.opaque (id INT NULL, bm_col BITMAP BITMAP_UNION NOT NULL DEFAULT BITMAP_EMPTY, hll_col HLL HLL_UNION NOT NULL, aggst_col AGG_STATE<max_by(int NULL, int NULL)> GENERIC NOT NULL) AGGREGATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')""",
        """INSERT INTO p0_probe.opaque SELECT 1, to_bitmap(100), hll_hash('h1'), max_by_state(1, 10)""",
        """INSERT INTO p0_probe.opaque SELECT 2, to_bitmap(200), hll_hash('h2'), max_by_state(2, 20)""",
        """CREATE TABLE p0_probe.nums (n BIGINT NULL) DUPLICATE KEY(n) DISTRIBUTED BY HASH(n) BUCKETS 4 PROPERTIES ('replication_num'='1')""",
        """INSERT INTO p0_probe.nums SELECT number FROM numbers('number' = '1001000')""",
    )
}
