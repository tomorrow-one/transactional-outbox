package one.tomorrow.transactionaloutbox;

import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.service.OutboxLockService;
import org.flywaydb.test.junit5.annotation.FlywayTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.junit.jupiter.Testcontainers;

@ExtendWith(SpringExtension.class)
@ActiveProfiles("test")
@ContextConfiguration(classes = {
        OutboxLockRepository.class,
        OutboxLock.class,
        OutboxLockService.class,
        IntegrationTestConfig.class
})
@Testcontainers
@FlywayTestExtension
public abstract class AbstractIntegrationTest {

    public static ProxiedPostgreSQLContainer postgresqlContainer = ProxiedPostgreSQLContainer.startProxiedPostgres();
    public static final ProxiedKafkaContainer kafkaContainer = ProxiedKafkaContainer.startProxiedKafka();

    protected Logger logger = LoggerFactory.getLogger(getClass());

    @DynamicPropertySource
    public static void setR2DBCProperties(DynamicPropertyRegistry registry) {
        ProxiedPostgreSQLContainer.setConnectionProperties(registry);
        registry.add("spring.r2dbc.pool.enabled", () -> "true");
        registry.add("spring.r2dbc.pool.initial-size", () -> "5");
        registry.add("spring.r2dbc.pool.max-size", () -> "10");
    }

}
