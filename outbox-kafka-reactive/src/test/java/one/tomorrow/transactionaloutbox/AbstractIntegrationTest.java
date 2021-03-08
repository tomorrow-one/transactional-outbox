package one.tomorrow.transactionaloutbox;

import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import org.flywaydb.test.junit5.annotation.FlywayTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.junit.jupiter.Testcontainers;

import static one.tomorrow.transactionaloutbox.IntegrationTestConfig.postgresqlContainer;
import static org.testcontainers.containers.PostgreSQLContainer.POSTGRESQL_PORT;

@ExtendWith(SpringExtension.class)
@ActiveProfiles("test")
@ContextConfiguration(classes = {
        OutboxLockRepository.class,
        OutboxLock.class,
        IntegrationTestConfig.class
})
@Testcontainers
@FlywayTestExtension
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    public static void setR2DBCProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + postgresqlContainer.getContainerIpAddress() +
                ":" + postgresqlContainer.getMappedPort(POSTGRESQL_PORT) + "/" + postgresqlContainer.getDatabaseName() +
                "?statement_timeout=100&lock_timeout=100");
        registry.add("spring.r2dbc.username", () -> postgresqlContainer.getUsername());
        registry.add("spring.r2dbc.password", () -> postgresqlContainer.getPassword());

        registry.add("spring.r2dbc.pool.enabled", () -> "true");
        registry.add("spring.r2dbc.pool.initial-size", () -> "10");
        registry.add("spring.r2dbc.pool.max-size", () -> "50");
    }

}
