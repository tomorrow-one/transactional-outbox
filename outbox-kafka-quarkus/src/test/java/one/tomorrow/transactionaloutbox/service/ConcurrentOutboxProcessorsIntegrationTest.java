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
package one.tomorrow.transactionaloutbox.service;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.KafkaTestUtils;
import one.tomorrow.transactionaloutbox.ProxiedKafkaContainer;
import one.tomorrow.transactionaloutbox.ProxiedPostgreSQLContainer;
import one.tomorrow.transactionaloutbox.config.TestTransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.publisher.DefaultKafkaProducerFactory;
import one.tomorrow.transactionaloutbox.publisher.KafkaProducerMessagePublisherFactory;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.tracing.NoopTracingService;
import one.tomorrow.transactionaloutbox.tracing.TracingService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static java.util.stream.IntStream.range;
import static one.tomorrow.transactionaloutbox.KafkaTestUtils.*;
import static one.tomorrow.transactionaloutbox.ProxiedKafkaContainer.bootstrapServers;
import static one.tomorrow.transactionaloutbox.ProxiedKafkaContainer.startProxiedKafka;
import static one.tomorrow.transactionaloutbox.ProxiedPostgreSQLContainer.startProxiedPostgres;
import static one.tomorrow.transactionaloutbox.TestUtils.newHeaders;
import static one.tomorrow.transactionaloutbox.TestUtils.newRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(ConcurrentOutboxProcessorsIntegrationTest.class)
@SuppressWarnings("unused")
public class ConcurrentOutboxProcessorsIntegrationTest implements QuarkusTestProfile {

    private static final String topic = "topicConcurrentTest";
    public static final ProxiedPostgreSQLContainer postgresqlContainer = startProxiedPostgres();
    public static final ProxiedKafkaContainer kafkaContainer = startProxiedKafka();
    private static Consumer<String, byte[]> consumer;

    @Inject
    EntityManager entityManager;
    @Inject
    OutboxRepository repository;
    @Inject
    TestOutboxLockRepository lockRepository;
    @Inject
    OutboxLockService lockService;
    @Inject
    TestOutboxRepository transactionalRepository;
    private final TracingService tracingService = new NoopTracingService();

    private OutboxProcessor testee1;
    private OutboxProcessor testee2;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "one.tomorrow.transactional-outbox.lock-timeout", "PT0.2S",
                // db config
                "quarkus.datasource.devservices.enabled", "false",
                "quarkus.datasource.jdbc.url", postgresqlContainer.getJdbcUrl(),
                "quarkus.datasource.username", postgresqlContainer.getUsername(),
                "quarkus.datasource.password", postgresqlContainer.getPassword(),
                // kafka config
                "quarkus.kafka.devservices.enabled", "false",
                "kafka.bootstrap.servers", kafkaContainer.getBootstrapServers()

        );
    }

    @BeforeAll
    static void beforeAll() {
        createTopic(bootstrapServers, topic);
        consumer = setupConsumer("testGroup", false, topic);
    }

    @BeforeEach
    @Transactional
    void cleanUp() {
        entityManager
                .createQuery("DELETE FROM OutboxRecord")
                .executeUpdate();
    }

    @AfterEach
    void afterTest() {
        testee1.close();
        testee2.close();
    }

    @AfterAll
    static void afterClass() {
        if (consumer != null)
            consumer.close();
    }

    @Test
    void should_ProcessRecordsOnceInOrder() {
        // given
        Duration lockTimeout = Duration.ofMillis(20); // very aggressive lock stealing
        Duration processingInterval = Duration.ZERO;
        KafkaProducerMessagePublisherFactory publisherFactory = new KafkaProducerMessagePublisherFactory(
                new DefaultKafkaProducerFactory(producerProps()));
        String eventSource = "test";

        TransactionalOutboxConfig config1 = TestTransactionalOutboxConfig.createConfig(
                processingInterval, lockTimeout, "processor1", eventSource);

        TransactionalOutboxConfig config2 = TestTransactionalOutboxConfig.createConfig(
                processingInterval, lockTimeout, "processor2", eventSource);

        testee1 = new OutboxProcessor(config1, repository, publisherFactory, lockService, tracingService);
        testee2 = new OutboxProcessor(config2, repository, publisherFactory, lockService, tracingService);

        // when
        List<OutboxRecord> outboxRecords = range(0, 1000).mapToObj(
                i -> newRecord(topic, "key1", "value" + i, newHeaders("h", "v" + i))
        ).toList();
        outboxRecords.forEach(transactionalRepository::persist);

        // then
        List<ConsumerRecord<String, byte[]>> allRecords = new ArrayList<>();
        while (allRecords.size() < outboxRecords.size()) {
            ConsumerRecords<String, byte[]> records = KafkaTestUtils.getRecords(consumer(), Duration.ofSeconds(5));
            records.iterator().forEachRemaining(allRecords::add);
        }

        assertEquals(outboxRecords.size(), allRecords.size());
        Iterator<ConsumerRecord<String, byte[]>> iter = allRecords.iterator();
        outboxRecords.forEach(outboxRecord -> {
            ConsumerRecord<String, byte[]> kafkaRecord = iter.next();
            assertConsumedRecord(outboxRecord, eventSource, kafkaRecord);
        });
    }

    private static Consumer<String, byte[]> consumer() {
        return consumer;
    }

}
