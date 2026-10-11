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
package one.tomorrow.transactionaloutbox.commons;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.EnumMap;
import java.util.Map;

/** Real database fixtures shared by JDBC and Reactive compatibility contracts. */
public final class DatabaseTestSupport implements AutoCloseable {
    private final Map<DatabaseDialect, JdbcDatabaseContainer<?>> containers = new EnumMap<>(DatabaseDialect.class);

    public DatabaseTestSupport() {
        containers.put(DatabaseDialect.POSTGRESQL, new PostgreSQLContainer("postgres:17"));
        containers.put(DatabaseDialect.MYSQL, new MySQLContainer("mysql:8.4"));
        try {
            containers.forEach((dialect, container) -> {
                container.start();
                new ResourceDatabasePopulator(new ClassPathResource(
                        "one/tomorrow/transactionaloutbox/schema/" + dialect.name().toLowerCase(java.util.Locale.ROOT) + ".sql"))
                        .execute(dataSource(dialect));
                new JdbcTemplate(dataSource(dialect)).execute("create table business_record (id bigint primary key)");
            });
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    public DataSource dataSource(DatabaseDialect dialect) {
        JdbcDatabaseContainer<?> container = containers.get(dialect);
        String url = container.getJdbcUrl();
        if (dialect == DatabaseDialect.MYSQL)
            url += (url.contains("?") ? "&" : "?") + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        return new DriverManagerDataSource(url, container.getUsername(), container.getPassword());
    }

    public String r2dbcUrl(DatabaseDialect dialect) {
        JdbcDatabaseContainer<?> container = containers.get(dialect);
        return "r2dbc:" + dialect.name().toLowerCase(java.util.Locale.ROOT) + "://"
                + container.getUsername() + ":" + container.getPassword() + "@" + container.getHost() + ":"
                + container.getFirstMappedPort() + "/" + container.getDatabaseName();
    }

    public void clean(DatabaseDialect dialect) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(dialect));
        jdbc.update("delete from outbox_kafka");
        jdbc.update("delete from outbox_kafka_lock");
        jdbc.update("delete from business_record");
    }

    @Override
    public void close() {
        containers.values().forEach(JdbcDatabaseContainer::stop);
    }
}
