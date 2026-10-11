/**
 * Copyright 2026 Tomorrow GmbH @ https://tomorrow.one
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package one.tomorrow.transactionaloutbox.repository;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;
import java.util.Map;

public class MySqlTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.datasource.db-kind", "mysql",
                "quarkus.datasource.devservices.enabled", "false",
                "quarkus.flyway.migrate-at-start", "false",
                "quarkus.hibernate-orm.jdbc.timezone", "UTC",
                "quarkus.kafka.devservices.enabled", "false");
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(MySqlDatabaseResource.class));
    }

    /** Keep the MySQL connection settings scoped to this profile, not shared Dev Services state. */
    public static class MySqlDatabaseResource implements QuarkusTestResourceLifecycleManager {
        private MySQLContainer database;

        @Override
        public Map<String, String> start() {
            database = new MySQLContainer("mysql:8.4")
                    .withDatabaseName("outbox_test")
                    // Connector/J telemetry must not initialize across Quarkus test class loaders.
                    .withUrlParam("openTelemetry", "DISABLED")
                    .withUrlParam("connectionTimeZone", "UTC")
                    .withUrlParam("forceConnectionTimeZoneToSession", "true")
                    .withInitScript("one/tomorrow/transactionaloutbox/schema/mysql.sql");
            database.start();
            return Map.of(
                    "quarkus.datasource.jdbc.url", database.getJdbcUrl(),
                    "quarkus.datasource.username", database.getUsername(),
                    "quarkus.datasource.password", database.getPassword());
        }

        @Override
        public void stop() {
            if (database != null) {
                database.stop();
                database = null;
            }
        }
    }
}
