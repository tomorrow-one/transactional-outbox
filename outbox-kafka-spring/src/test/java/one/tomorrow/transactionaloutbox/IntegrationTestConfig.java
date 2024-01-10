/**
 * Copyright 2022 Tomorrow GmbH @ https://tomorrow.one
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
package one.tomorrow.transactionaloutbox;

import org.apache.commons.dbcp2.BasicDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.test.FlywayHelperFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Properties;

@Configuration
@EnableTransactionManagement
public class IntegrationTestConfig {

    public static final Duration DEFAULT_OUTBOX_LOCK_TIMEOUT = Duration.ofMillis(200);
    public static ProxiedPostgreSQLContainer postgresqlContainer = ProxiedPostgreSQLContainer.startProxiedPostgres();

    @Bean
    public DataSource dataSource() {
        BasicDataSource dataSource = new BasicDataSource();

        dataSource.setDriverClassName(postgresqlContainer.getDriverClassName());
        dataSource.setUrl(postgresqlContainer.getJdbcUrl());
        dataSource.setUsername(postgresqlContainer.getUsername());
        dataSource.setPassword(postgresqlContainer.getPassword());
        dataSource.setDefaultAutoCommit(false);

        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public Flyway flywayFactory(ClassicConfiguration configuration) {
        FlywayHelperFactory factory = new FlywayHelperFactory();

        factory.setFlywayConfiguration(configuration);
        factory.setFlywayProperties(new Properties());

        return factory.createFlyway();
    }

    @Bean
    public ClassicConfiguration flywayConfiguration(DataSource dataSource) {
        ClassicConfiguration configuration = new ClassicConfiguration();

        configuration.setDataSource(dataSource);
        configuration.setLocationsAsStrings("classpath:/db/migration");
        configuration.setCleanDisabled(false);

        return configuration;
    }

}
