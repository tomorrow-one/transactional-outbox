/**
 * Copyright 2022-2023 Tomorrow GmbH @ https://tomorrow.one
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

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class MySqlTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.datasource.db-kind", "mysql",
                "quarkus.datasource.devservices.image-name", "mysql:8.4",
                // Connector/J telemetry must not initialize across Quarkus test class loaders.
                "quarkus.datasource.devservices.properties.openTelemetry", "DISABLED",
                "quarkus.datasource.devservices.init-script-path", "one/tomorrow/transactionaloutbox/schema/mysql.sql",
                "quarkus.flyway.migrate-at-start", "false",
                "quarkus.hibernate-orm.jdbc.timezone", "UTC",
                "quarkus.kafka.devservices.enabled", "false");
    }
}
