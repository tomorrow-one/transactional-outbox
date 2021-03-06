package one.tomorrow.transactionaloutbox;

import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.flywaydb.test.junit5.annotation.FlywayTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.PostgreSQLContainer;
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
                ":" + postgresqlContainer.getMappedPort(POSTGRESQL_PORT) + "/" + postgresqlContainer.getDatabaseName());
        registry.add("spring.r2dbc.username", () -> postgresqlContainer.getUsername());
        registry.add("spring.r2dbc.password", () -> postgresqlContainer.getPassword());

        registry.add("spring.r2dbc.pool.enabled", () -> "true");
        registry.add("spring.r2dbc.pool.initial-size", () -> "10");
        registry.add("spring.r2dbc.pool.max-size", () -> "50");
    }

}
