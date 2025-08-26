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

import eu.rekawek.toxiproxy.model.toxic.Timeout;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.KafkaTestUtils;
import one.tomorrow.transactionaloutbox.ProxiedKafkaContainer;
import one.tomorrow.transactionaloutbox.ProxiedPostgreSQLContainer;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig.CleanupConfig;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.tracing.TracingAssertions;
import one.tomorrow.transactionaloutbox.tracing.TracingService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static eu.rekawek.toxiproxy.model.ToxicDirection.DOWNSTREAM;
import static java.lang.Thread.sleep;
import static one.tomorrow.transactionaloutbox.KafkaTestUtils.*;
import static one.tomorrow.transactionaloutbox.ProxiedKafkaContainer.bootstrapServers;
import static one.tomorrow.transactionaloutbox.ProxiedKafkaContainer.startProxiedKafka;
import static one.tomorrow.transactionaloutbox.ProxiedPostgreSQLContainer.postgresProxy;
import static one.tomorrow.transactionaloutbox.ProxiedPostgreSQLContainer.startProxiedPostgres;
import static one.tomorrow.transactionaloutbox.TestUtils.newHeaders;
import static one.tomorrow.transactionaloutbox.TestUtils.newRecord;
import static one.tomorrow.transactionaloutbox.config.TestTransactionalOutboxConfig.createCleanupConfig;
import static one.tomorrow.transactionaloutbox.config.TestTransactionalOutboxConfig.createConfig;
import static one.tomorrow.transactionaloutbox.tracing.TracingService.INTERNAL_PREFIX;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(OutboxProcessorIntegrationTest.class)
@SuppressWarnings({"unused", "resource"})
public class OutboxProcessorIntegrationTest implements QuarkusTestProfile, TracingAssertions {

    private static final String TOPIC_1 = "topicOPIT1";
    private static final String TOPIC_2 = "topicOPIT2";

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
    @Inject
    TracingService tracingService;
    @Inject
    InMemorySpanExporter spanExporter;
    @Inject
    OpenTelemetry openTelemetry;

    private OutboxProcessor testee;

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
        createTopic(bootstrapServers, TOPIC_1, TOPIC_2);
        consumer = setupConsumer("testConsumer-" + System.currentTimeMillis(), true, TOPIC_1, TOPIC_2);
    }

    @BeforeEach
    @Transactional
    void cleanUp() {
        entityManager
                .createQuery("DELETE FROM OutboxRecord")
                .executeUpdate();
        // Clear spans from previous test runs
        spanExporter.reset();
    }

    @AfterEach
    void afterTest() {
        testee.close();
    }

    @AfterAll
    static void afterAll() {
        consumer.close();
    }

    @Test
    void should_ProcessNewRecords() {
        // given
        String eventSource = "test";
        TransactionalOutboxConfig config = createConfig(
                Duration.ofMillis(50),
                Duration.ofMillis(200),
                "processor",
                eventSource
        );
        testee = new OutboxProcessor(config, repository, producerFactory(), lockService, tracingService);

        // when
        OutboxRecord record1 = newRecord(TOPIC_1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertThat(records.count(), is(1));
        ConsumerRecord<String, byte[]> kafkaRecord = records.iterator().next();
        assertConsumedRecord(record1, "h1", eventSource, kafkaRecord);

        // and when
        OutboxRecord record2 = newRecord(TOPIC_2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        // then
        records = getAndCommitRecords();
        assertThat(records.count(), is(1));
        kafkaRecord = records.iterator().next();
        assertConsumedRecord(record2, "h2", eventSource, kafkaRecord);
    }

    private ConsumerRecords<String, byte[]> getAndCommitRecords() {
        ConsumerRecords<String, byte[]> records = KafkaTestUtils.getRecords(consumer(), Duration.ofSeconds(10));
        consumer().commitSync();
        return records;
    }

    @Test
    void should_ProcessNewRecords_withTracing() {
        // given
        String eventSource = "test";
        TransactionalOutboxConfig config = createConfig(
                Duration.ofMillis(50),
                Duration.ofMillis(200),
                "processor",
                eventSource
        );
        testee = new OutboxProcessor(config, repository, producerFactory(), lockService, tracingService);

        // when

        String traceId1 = "0123456789abcdef0123456789abcdef"; // 32 hex
        String parentSpanId1 = "0123456789abcdef"; // 16 hex
        String traceparent1 = "00-" + traceId1 + "-" + parentSpanId1 + "-01";
        OutboxRecord record1 = newRecord(TOPIC_1, "key1", "value1", newHeaders(
                "h1", "v1",
                INTERNAL_PREFIX + "traceparent", traceparent1,
                INTERNAL_PREFIX + "tracestate", "vendor=value1"));
        transactionalRepository.persist(record1);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertEquals(1, records.count());
        ConsumerRecord<String, byte[]> kafkaRecord = records.iterator().next();
        assertConsumedRecord(record1, "h1", eventSource, kafkaRecord);

        // verify spans: one for the transactional-outbox, one for the processing to Kafka
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(2, spans.size());

        SpanData outboxSpan = findSpanByName(spanExporter, "transactional-outbox");
        assertOutboxSpan(outboxSpan, traceId1, parentSpanId1, record1);

        SpanData processingSpan = findSpanByName(spanExporter, "To_" + TOPIC_1);
        assertProcessingSpan(processingSpan, traceId1, outboxSpan.getSpanId(), TOPIC_1);

        // and when
        String traceId2 = "1123456789abcdef0123456789abcdef"; // 32 hex
        String parentSpanId2 = "1123456789abcdef"; // 16 hex
        String traceparent2 = "00-" + traceId2 + "-" + parentSpanId2 + "-01";
        OutboxRecord record2 = newRecord(TOPIC_2, "key2", "value2", newHeaders(
                "h2", "v2",
                INTERNAL_PREFIX + "traceparent", traceparent2,
                INTERNAL_PREFIX + "tracestate", "vendor=value2"));
        transactionalRepository.persist(record2);

        // then
        records = getAndCommitRecords();
        assertEquals(1, records.count());
        kafkaRecord = records.iterator().next();
        assertConsumedRecord(record2, "h2", eventSource, kafkaRecord);

        // verify spans: one for the transactional-outbox, one for the processing to Kafka
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(4, spanExporter.getFinishedSpanItems().size())
        );
        spans = spanExporter.getFinishedSpanItems();

        outboxSpan = spans.get(2);
        assertOutboxSpan(outboxSpan, traceId2, parentSpanId2, record2);

        processingSpan = spans.get(3);
        assertProcessingSpan(processingSpan, traceId2, outboxSpan.getSpanId(), TOPIC_2);
    }

    @Test
    void should_StartWhenKafkaIsNotAvailableAndProcessOutboxWhenKafkaIsAvailable() throws InterruptedException {
        // given
        OutboxRecord record1 = newRecord(TOPIC_1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        kafkaContainer.setConnectionCut(true);

        // when
        Duration processingInterval = Duration.ofMillis(50);
        String eventSource = "test";
        TransactionalOutboxConfig config = createConfig(
                processingInterval,
                Duration.ofMillis(200),
                "processor",
                eventSource
        );
        testee = new OutboxProcessor(config, repository, producerFactory(), lockService, tracingService);

        sleep(processingInterval.plusMillis(200).toMillis());
        kafkaContainer.setConnectionCut(false);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertThat(records.count(), is(1));
        assertConsumedRecord(record1, eventSource, records.iterator().next());
    }

    @Test
    void should_ContinueProcessingAfterKafkaRestart() throws InterruptedException {
        // given
        OutboxRecord record1 = newRecord(TOPIC_1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        Duration processingInterval = Duration.ofMillis(50);
        String eventSource = "test";
        TransactionalOutboxConfig config = createConfig(
                processingInterval,
                Duration.ofMillis(200),
                "processor",
                eventSource
        );
        testee = new OutboxProcessor(config, repository, producerFactory(), lockService, tracingService);

        // when
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();

        // then
        assertThat(records.count(), is(1));

        // and when
        kafkaContainer.setConnectionCut(true);

        OutboxRecord record2 = newRecord(TOPIC_2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        sleep(processingInterval.plusMillis(200).toMillis());
        kafkaContainer.setConnectionCut(false);

        // then
        records = getAndCommitRecords();
        assertThat(records.count(), is(1));
        assertConsumedRecord(record2, "h2", eventSource, records.iterator().next());
    }

    @Test
    void should_ContinueProcessingAfterDatabaseUnavailability() throws InterruptedException, IOException {
        // given
        OutboxRecord record1 = newRecord(TOPIC_1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        Duration processingInterval = Duration.ofMillis(20);
        String eventSource = "test";
        TransactionalOutboxConfig config = createConfig(
                processingInterval,
                Duration.ofMillis(200),
                "processor",
                eventSource
        );
        testee = new OutboxProcessor(config, repository, producerFactory(), lockService, tracingService);

        // when
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();

        // then
        assertEquals(1, records.count());

        // and when
        Timeout timeout = postgresProxy.toxics().timeout("TIMEOUT", DOWNSTREAM, 1L);
        sleep(processingInterval.multipliedBy(5).toMillis());
        timeout.remove();

        OutboxRecord record2 = newRecord(TOPIC_2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persistWithRetry(record2);

        // then
        records = getAndCommitRecords();
        assertEquals(1, records.count());
        assertConsumedRecord(record2, "h2", eventSource, records.iterator().next());
    }

    @Test
    void should_ContinueProcessingAfterDbConnectionFailureInLockAcquisition() throws InterruptedException {
        // given
        Duration processingInterval = Duration.ofMillis(50);
        String eventSource = "test";

        TransactionalOutboxConfig config = createConfig(
                processingInterval,
                Duration.ofMillis(200),
                "processor",
                eventSource
        );
        testee = new OutboxProcessor(config, repository, producerFactory(), lockService, tracingService);

        // when
        OutboxRecord record1 = newRecord(TOPIC_1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertThat(records.count(), is(1));

        // and when
        lockRepository.failAcquireOrRefreshLock().set(true);

        OutboxRecord record2 = newRecord(TOPIC_2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        lockRepository.acquireOrRefreshLockCDL().await();
        lockRepository.failAcquireOrRefreshLock().set(false);

        // then
        records = getAndCommitRecords();
        assertThat(records.count(), is(1));
    }

    @Test
    void should_ContinueProcessingAfterDbConnectionFailureInPreventLockStealing() throws InterruptedException {
        // given
        Duration processingInterval = Duration.ofMillis(500);
        String eventSource = "test";

        TransactionalOutboxConfig config = createConfig(
                processingInterval,
                Duration.ofMillis(200),
                "processor",
                eventSource
        );
        testee = new OutboxProcessor(config, repository, producerFactory(), lockService, tracingService);

        // when
        OutboxRecord record1 = newRecord(TOPIC_1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertThat(records.count(), is(1));

        // and when
        lockRepository.failPreventLockStealing().set(true);

        OutboxRecord record2 = newRecord(TOPIC_2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        lockRepository.preventLockStealingCDL().await();
        lockRepository.failPreventLockStealing().set(false);

        // then
        records = getAndCommitRecords();
        assertThat(records.count(), is(1));
    }

    @Test
    void should_CleanupOutdatedProcessedRecords() {
        // given
        String eventSource = "test";
        CleanupConfig cleanupConfig = createCleanupConfig(
                Duration.ofMillis(100),
                Duration.ofMillis(200)
        );
        TransactionalOutboxConfig config = createConfig(
                Duration.ofMillis(10),
                Duration.ofMillis(200),
                "processor",
                eventSource,
                cleanupConfig
        );
        testee = new OutboxProcessor(config, repository, producerFactory(), lockService, tracingService);

        // when
        OutboxRecord record1 = newRecord(TOPIC_1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);
        assertEquals(1, repository.getUnprocessedRecords(1).size());

        // then
        await().atMost(Duration.ofSeconds(5)).until(
                () -> repository.getUnprocessedRecords(1).isEmpty()
        );
        assertEquals(1, getAndCommitRecords().count());

        // and eventually
        await().atMost(Duration.ofSeconds(5)).until(
                () -> countOutboxRecords() == 0
        );
    }

    @Transactional
    long countOutboxRecords() {
        return entityManager
                .createQuery("SELECT COUNT(r) FROM OutboxRecord r", Long.class)
                .getSingleResult();
    }

    private DefaultKafkaProducerFactory producerFactory() {
        return new DefaultKafkaProducerFactory(producerProps());
    }

    private static Consumer<String, byte[]> consumer() {
        return consumer;
    }

}
