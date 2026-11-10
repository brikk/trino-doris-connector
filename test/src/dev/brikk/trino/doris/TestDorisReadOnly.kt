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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisReadOnly : DorisPushdownTest("p1_readonly", { provisionFixture() }) {

    private fun rowCount(): Long = DorisTestCluster.queryScalar("SELECT COUNT(*) FROM p1_readonly.t")!!.toLong()

    private fun assertNoSideEffects() {
        assertThat(rowCount()).isEqualTo(2)
        assertThat(DorisTestCluster.queryScalar("SELECT v FROM p1_readonly.t WHERE id = 1")).isEqualTo("one")
        assertThat(DorisTestCluster.queryScalar("SELECT v FROM p1_readonly.t WHERE id = 2")).isEqualTo("two")
    }


    @Test
    fun testCreateSchemaDenied() {
        assertQueryFails("CREATE SCHEMA doris.p1_denied_schema", "Access Denied: Cannot create schema p1_denied_schema")
        assertThat(DorisTestCluster.querySingleColumn("SHOW DATABASES")).doesNotContain("p1_denied_schema")
    }

    @Test
    fun testDropAndRenameSchemaDenied() {
        // Plain DROP SCHEMA hits the engine's non-empty check first; CASCADE reaches the
        // connector access-control boundary.
        assertQueryFails("DROP SCHEMA doris.p1_readonly CASCADE", "Access Denied: Cannot drop schema p1_readonly")
        assertQueryFails("ALTER SCHEMA doris.p1_readonly RENAME TO p1_renamed", "Access Denied: Cannot rename schema from p1_readonly to p1_renamed")
        assertThat(DorisTestCluster.querySingleColumn("SHOW DATABASES")).contains("p1_readonly")
        assertNoSideEffects()
    }

    @Test
    fun testCreateTableAndCtasDenied() {
        assertQueryFails("CREATE TABLE doris.p1_readonly.t_new (x integer)", "Access Denied: Cannot create table p1_readonly.t_new")
        assertQueryFails(
            "CREATE TABLE doris.p1_readonly.t_ctas AS SELECT * FROM doris.p1_readonly.t",
            "Access Denied: Cannot create table p1_readonly.t_ctas",
        )
        assertThat(DorisTestCluster.querySingleColumn("SHOW TABLES FROM p1_readonly"))
            .containsExactly("t")
    }

    @Test
    fun testCreateViewDenied() {
        assertQueryFails(
            "CREATE VIEW doris.p1_readonly.denied_view AS SELECT * FROM doris.p1_readonly.t",
            "Access Denied: Cannot create view p1_readonly.denied_view",
        )
        assertThat(DorisTestCluster.querySingleColumn("SHOW TABLES FROM p1_readonly")).doesNotContain("denied_view")
    }

    @Test
    fun testDropTableDenied() {
        assertQueryFails("DROP TABLE doris.p1_readonly.t", "Access Denied: Cannot drop table p1_readonly.t")
        assertThat(DorisTestCluster.querySingleColumn("SHOW TABLES FROM p1_readonly")).contains("t")
        assertNoSideEffects()
    }

    @Test
    fun testAlterTableDenied() {
        assertQueryFails("ALTER TABLE doris.p1_readonly.t ADD COLUMN z integer", "Access Denied: Cannot add a column to table p1_readonly.t")
        assertQueryFails("ALTER TABLE doris.p1_readonly.t DROP COLUMN v", "Access Denied: Cannot drop a column from table p1_readonly.t")
        assertQueryFails("ALTER TABLE doris.p1_readonly.t RENAME COLUMN v TO w", "Access Denied: Cannot rename a column in table p1_readonly.t")
        assertQueryFails(
            "ALTER TABLE doris.p1_readonly.t ALTER COLUMN v SET DATA TYPE varchar(100)",
            "Access Denied: Cannot alter a column in table p1_readonly.t",
        )
        assertQueryFails(
            "ALTER TABLE doris.p1_readonly.t SET PROPERTIES comment = 'denied'",
            "Access Denied: Cannot set table properties to p1_readonly.t",
        )
        assertQueryFails(
            "ALTER TABLE doris.p1_readonly.t RENAME TO doris.p1_readonly.t2",
            "Access Denied: Cannot rename table from p1_readonly.t to p1_readonly.t2",
        )
        assertThat(DorisTestCluster.querySingleColumn("SHOW TABLES FROM p1_readonly")).containsExactly("t")
        assertNoSideEffects()
    }

    @Test
    fun testCommentsDenied() {
        assertQueryFails("COMMENT ON TABLE doris.p1_readonly.t IS 'nope'", "Access Denied: Cannot comment table to p1_readonly.t")
        assertQueryFails("COMMENT ON COLUMN doris.p1_readonly.t.v IS 'nope'", "Access Denied: Cannot comment column to p1_readonly.t")
        assertNoSideEffects()
    }


    @Test
    fun testInsertDenied() {
        assertQueryFails("INSERT INTO doris.p1_readonly.t VALUES (3, 'three')", "Access Denied: Cannot insert into table p1_readonly.t")
        assertNoSideEffects()
    }

    @Test
    fun testDeleteIsDeniedTheStockDeleteDangerIsClosed() {
        assertQueryFails("DELETE FROM doris.p1_readonly.t WHERE id = 1", "Access Denied: Cannot delete from table p1_readonly.t")
        assertQueryFails("DELETE FROM doris.p1_readonly.t", "Access Denied: Cannot delete from table p1_readonly.t")
        assertNoSideEffects()
    }

    @Test
    fun testUpdateDenied() {
        assertQueryFails("UPDATE doris.p1_readonly.t SET v = 'hacked' WHERE id = 1", "Access Denied: Cannot update columns \\[v\\] in table p1_readonly.t")
        assertNoSideEffects()
    }

    @Test
    fun testMergeDenied() {
        assertQueryFails(
            """
            MERGE INTO doris.p1_readonly.t AS target
            USING (VALUES (1, 'merged')) AS source(id, v)
            ON target.id = source.id
            WHEN MATCHED THEN UPDATE SET v = source.v
            WHEN NOT MATCHED THEN INSERT (id, v) VALUES (source.id, source.v)
            """.trimIndent(),
            "Access Denied: Cannot insert into table p1_readonly.t",
        )
        assertNoSideEffects()
    }

    @Test
    fun testTruncateDenied() {
        assertQueryFails("TRUNCATE TABLE doris.p1_readonly.t", "Access Denied: Cannot truncate table p1_readonly.t")
        assertNoSideEffects()
    }


    @Test
    fun testSystemExecuteIsDeniedAndHasNoSideEffects() {
        // Base JDBC installs system.execute even though it bypasses the connector's statement builder.
        assertQueryFails(
            "CALL doris.system.execute('INSERT INTO p1_readonly.t VALUES (99, ''hack'')')",
            "Access Denied: Cannot execute procedure system.execute.*",
        )
        assertQueryFails(
            "CALL doris.system.execute('DELETE FROM p1_readonly.t WHERE id = 1')",
            "Access Denied: Cannot execute procedure system.execute.*",
        )
        assertQueryFails(
            "CALL doris.system.execute('CREATE DATABASE p1_execute_hack')",
            "Access Denied: Cannot execute procedure system.execute.*",
        )
        assertThat(DorisTestCluster.queryScalar("SELECT COUNT(*) FROM p1_readonly.t WHERE id = 99")).isEqualTo("0")
        assertThat(DorisTestCluster.querySingleColumn("SHOW DATABASES")).doesNotContain("p1_execute_hack")
        assertNoSideEffects()
    }

    @Test
    fun testRawQueryTableFunctionIsNotRegistered() {
        assertQueryFails(
            "SELECT * FROM TABLE(doris.system.query(query => 'SELECT 1'))",
            "line 1:21: Table function 'doris.system.query' not registered",
        )
    }

    @Test
    fun testFlushMetadataCacheIsAllowedAsHarmless() {
        // PLAN §7: harmless metadata-cache flush remains available.
        assertQuerySucceeds("CALL doris.system.flush_metadata_cache()")
    }

    @Test
    fun testReadsStillWork() {
        assertThat(query("SELECT id, v FROM doris.p1_readonly.t"))
            .matches("VALUES (1, CAST('one' AS varchar(20))), (2, CAST('two' AS varchar(20)))")
    }

    companion object {
        private fun provisionFixture() {
            provisionFixture(
                "p1_readonly",
                """
                CREATE TABLE p1_readonly.t (id INT NOT NULL, v VARCHAR(20))
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1
                PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                "INSERT INTO p1_readonly.t VALUES (1, 'one'), (2, 'two')",
            )
        }
    }
}
