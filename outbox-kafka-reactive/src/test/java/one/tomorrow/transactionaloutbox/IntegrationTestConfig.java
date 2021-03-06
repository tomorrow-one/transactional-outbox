package one.tomorrow.transactionaloutbox;

import org.apache.commons.dbcp.BasicDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.test.FlywayHelperFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Properties;

@Configuration
@EnableAutoConfiguration
// @ImportAutoConfiguration(R2dbcTransactionManagerAutoConfiguration.class)
@EnableTransactionManagement
@EnableR2dbcRepositories
public class IntegrationTestConfig {

    public static PostgreSQLContainer<?> postgresqlContainer = new PostgreSQLContainer<>("postgres:10.8");

    static {
        postgresqlContainer.start();
    }

    public static final Duration DEFAULT_OUTBOX_LOCK_TIMEOUT = Duration.ofMillis(200);

    @Bean
    public DataSource dataSource() {
        BasicDataSource dataSource = new BasicDataSource();

        dataSource.setDriverClassName(postgresqlContainer.getDriverClassName());
        dataSource.setUrl(postgresqlContainer.getJdbcUrl());
        dataSource.setUsername(postgresqlContainer.getUsername());
        dataSource.setPassword(postgresqlContainer.getPassword());

        return dataSource;
    }

    @Bean
    public Flyway flywayFactory(ClassicConfiguration configuration) {

        System.out.println("*** creating flyway with " + configuration.getDataSource());
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

        return configuration;
    }

}
