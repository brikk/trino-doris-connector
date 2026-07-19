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
package dev.brikk.doris.trino.plugin

import io.trino.plugin.jdbc.JdbcPlugin

/**
 * Read-only Trino connector for Apache Doris (baseline: Doris 4.1.3, Trino 483).
 *
 * Connector name `doris` (PLAN G10). Base JDBC over MySQL Connector/J against the
 * Doris FE MySQL-protocol port (PLAN G1/G8).
 */
class DorisPlugin : JdbcPlugin("doris", ::DorisClientModule)
