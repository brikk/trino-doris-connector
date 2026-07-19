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

import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException

/**
 * SELECT-only fixture account (PLAN G7.1, §7, §10-P1): the `trino_ro` Doris user holds only
 * `SELECT_PRIV` on the internal catalog, so even IF every connector-code layer regressed,
 * Doris itself denies writes. The GRANT statements are documented in `compose/README.md`.
 *
 * The stock DELETE incident (STOCK) proved code-only enforcement is insufficient — this
 * server-side belt is independent of connector code by design.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisTrinoRoAccount : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionReadOnlyUser()
        return DorisQueryRunner.builder()
            .addConnectorProperty("connection-user", READ_ONLY_USER)
            .build()
    }

    @Test
    fun testReadsWorkAsTrinoRo() {
        assertThat(computeActual("SHOW SCHEMAS FROM doris").onlyColumnAsSet).contains("p0_probe")
        assertThat(query("SELECT count(*) FROM doris.p0_probe.scalars_view")).matches("VALUES BIGINT '8'")
        assertThat(query("SELECT n FROM doris.p0_probe.nums WHERE n > 1000995")).isFullyPushedDown()
    }

    @Test
    fun testDorisItselfDeniesWritesForTrinoRo() {
        // Belt independent of connector code: raw mutating statements over direct JDBC as
        // trino_ro must be denied BY DORIS.
        assertDorisDenies("INSERT INTO p0_probe.nums VALUES (999999999)")
        assertDorisDenies("DELETE FROM p0_probe.nums WHERE n = 0")
        // UPDATE needs a UNIQUE-key table to get past Doris's table-model check and reach the
        // privilege check (a DUP-table UPDATE fails on the model check instead — STOCK saw the
        // same); the fixture provisions p1_ro_smoke.uniq for exactly this.
        assertDorisDenies("UPDATE p1_ro_smoke.uniq SET v = 'hacked' WHERE id = 1")
        assertDorisDenies("TRUNCATE TABLE p0_probe.nums")
        assertDorisDenies("DROP TABLE p0_probe.nums")
        assertDorisDenies("CREATE TABLE p0_probe.hack (x INT) DISTRIBUTED BY HASH(x) BUCKETS 1 PROPERTIES('replication_num'='1')")
        assertDorisDenies("CREATE DATABASE p1_ro_hack")
        assertDorisDenies("ALTER TABLE p0_probe.nums RENAME nums_hacked")

        // ... while reads on the same connection are fine.
        DorisTestCluster.openConnection(READ_ONLY_USER).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM p0_probe.scalars_view").use { resultSet ->
                    assertThat(resultSet.next()).isTrue()
                    assertThat(resultSet.getLong(1)).isEqualTo(8)
                }
            }
        }
        // No side effects reached Doris.
        assertThat(DorisTestCluster.queryScalar("SELECT COUNT(*) FROM p0_probe.nums")).isEqualTo("1001000")
        assertThat(DorisTestCluster.queryScalar("SELECT v FROM p1_ro_smoke.uniq WHERE id = 1")).isEqualTo("one")
        assertThat(DorisTestCluster.querySingleColumn("SHOW DATABASES")).doesNotContain("p1_ro_hack")
    }

    private fun assertDorisDenies(sql: String) {
        DorisTestCluster.openConnection(READ_ONLY_USER).use { connection ->
            connection.createStatement().use { statement ->
                assertThatThrownBy { statement.execute(sql) }
                    .describedAs(sql)
                    .isInstanceOf(SQLException::class.java)
                    .hasMessageContainingAll("denied")
            }
        }
    }

    companion object {
        private const val READ_ONLY_USER = "trino_ro"

        /**
         * GRANTs mirrored in compose/README.md. SELECT_PRIV on the internal catalog only —
         * proven live: INSERT/DELETE fail with "LOAD command denied", DDL with "Access denied".
         */
        private fun provisionReadOnlyUser() {
            DorisTestCluster.executeAsRoot(
                "DROP USER IF EXISTS '$READ_ONLY_USER'@'%'",
                "CREATE USER '$READ_ONLY_USER'@'%' IDENTIFIED BY ''",
                "GRANT SELECT_PRIV ON internal.*.* TO '$READ_ONLY_USER'@'%'",
                // UNIQUE-key target for the UPDATE privilege-denial belt test.
                "DROP DATABASE IF EXISTS p1_ro_smoke",
                "CREATE DATABASE p1_ro_smoke",
                """
                CREATE TABLE p1_ro_smoke.uniq (id INT NOT NULL, v VARCHAR(20))
                UNIQUE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1
                PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                "INSERT INTO p1_ro_smoke.uniq VALUES (1, 'one')",
            )
        }
    }
}
