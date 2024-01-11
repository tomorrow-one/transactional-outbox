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
package one.tomorrow.transactionaloutbox.reactive;

import one.tomorrow.transactionaloutbox.commons.ProxiedKafkaContainer;
import one.tomorrow.transactionaloutbox.commons.ProxiedPostgreSQLContainer;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxLock;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.reactive.service.OutboxLockService;
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
