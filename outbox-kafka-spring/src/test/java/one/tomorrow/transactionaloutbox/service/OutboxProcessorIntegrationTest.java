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
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTraceContext;
import io.micrometer.tracing.test.simple.SimpleTracer;
import one.tomorrow.transactionaloutbox.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.KafkaTestSupport;
import one.tomorrow.transactionaloutbox.commons.ProxiedKafkaContainer;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.service.OutboxProcessor.CleanupSettings;
import one.tomorrow.transactionaloutbox.tracing.MicrometerTracingIntegrationTestConfig;
import one.tomorrow.transactionaloutbox.tracing.TracingAssertions;
import one.tomorrow.transactionaloutbox.tracing.TracingService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import static eu.rekawek.toxiproxy.model.ToxicDirection.DOWNSTREAM;
import static one.tomorrow.transactionaloutbox.IntegrationTestConfig.DEFAULT_OUTBOX_LOCK_TIMEOUT;
import static one.tomorrow.transactionaloutbox.KafkaTestSupport.*;
import static one.tomorrow.transactionaloutbox.commons.ProxiedKafkaContainer.bootstrapServers;
import static one.tomorrow.transactionaloutbox.commons.ProxiedPostgreSQLContainer.postgresProxy;
import static one.tomorrow.transactionaloutbox.TestUtils.newHeaders;
import static one.tomorrow.transactionaloutbox.TestUtils.newRecord;
import static one.tomorrow.transactionaloutbox.commons.CommonKafkaTestSupport.*;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SOURCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.Longs.toLong;
import static one.tomorrow.transactionaloutbox.tracing.TracingService.INTERNAL_PREFIX;
import static one.tomorrow.transactionaloutbox.tracing.SimplePropagator.TRACING_SPAN_ID;
import static one.tomorrow.transactionaloutbox.tracing.SimplePropagator.TRACING_TRACE_ID;
import static org.apache.kafka.clients.producer.ProducerConfig.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        OutboxRecord.class,
        OutboxRepository.class,
        TransactionalOutboxRepository.class,
        OutboxLock.class,
        OutboxLockRepository.class,
        IntegrationTestConfig.class,
        MicrometerTracingIntegrationTestConfig.class
})
@TestExecutionListeners({
        DependencyInjectionTestExecutionListener.class,
        FlywayTestExecutionListener.class
})
@FlywayTest
@SuppressWarnings("unused")
public class OutboxProcessorIntegrationTest implements KafkaTestSupport<byte[]>, TracingAssertions {

    public static final ProxiedKafkaContainer kafkaContainer = ProxiedKafkaContainer.startProxiedKafka();
    private static final String topic1 = "topicOPIT1";
    private static final String topic2 = "topicOPIT2";
    private static final AtomicInteger processorIdx = new AtomicInteger(0);
    private static Consumer<String, byte[]> consumer;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OutboxRepository repository;
    @Autowired
    private TransactionalOutboxRepository transactionalRepository;
    @Autowired
    private AutowireCapableBeanFactory beanFactory;
    @Autowired
    private TracingService tracingService;
    @Autowired
    private SimpleTracer tracer;

    private OutboxProcessor testee;

    @BeforeAll
    public static void beforeAll() {
        createTopic(bootstrapServers, topic1, topic2);
    }

    @AfterAll
    public static void afterAll() {
        if (consumer != null)
            consumer.close();
    }

    @AfterEach
    public void afterTest() {
        testee.close();
    }

    private static String lockOwnerId() {
        return "processor-" + processorIdx.incrementAndGet();
    }

    @Test
    public void should_ProcessNewRecords() {
        // given
        String eventSource = "test";
        testee = new OutboxProcessor(repository,
                producerFactory(),
                Duration.ofMillis(50),
                DEFAULT_OUTBOX_LOCK_TIMEOUT,
                lockOwnerId(),
                eventSource,
                beanFactory);

        // when
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertEquals(1, records.count(), "Have records with keys: " + keys(records));
        ConsumerRecord<String, byte[]> kafkaRecord = records.iterator().next();
        assertConsumedRecord(record1, "h1", eventSource, kafkaRecord);

        // and when
        OutboxRecord record2 = newRecord(topic2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        // then
        records = getAndCommitRecords();
        assertEquals(1, records.count());
        kafkaRecord = records.iterator().next();
        assertConsumedRecord(record2, "h2", eventSource, kafkaRecord);
    }

    @Test
    public void should_ProcessNewRecords_withTracing() {
        // given
        String eventSource = "test";
        testee = new OutboxProcessor(repository,
                producerFactory(),
                Duration.ofMillis(50),
                DEFAULT_OUTBOX_LOCK_TIMEOUT,
                lockOwnerId(),
                eventSource,
                null,
                tracingService,
                beanFactory);

        // when
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders(
                "h1", "v1",
                INTERNAL_PREFIX + TRACING_TRACE_ID, "traceId-1",
                INTERNAL_PREFIX + TRACING_SPAN_ID, "spanId-1"));
        transactionalRepository.persist(record1);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertEquals(1, records.count(), "Have records with keys: " + keys(records));
        ConsumerRecord<String, byte[]> kafkaRecord = records.iterator().next();
        assertConsumedRecord(record1, "h1", eventSource, kafkaRecord);
        // verify spans: one for the transactional-outbox, one for the processing to Kafka
        assertEquals(2, tracer.getSpans().size());
        SimpleSpan outboxSpan = tracer.getSpans().getFirst();
        assertOutboxSpan(outboxSpan, "traceId-1", "spanId-1", record1);
        SimpleTraceContext processingSpanContext = tracer.getSpans().getLast().context();
        assertProcessingSpan(processingSpanContext, "traceId-1", outboxSpan.context().spanId());

        // and when
        OutboxRecord record2 = newRecord(topic2, "key2", "value2", newHeaders(
                "h2", "v2",
                INTERNAL_PREFIX + TRACING_TRACE_ID, "traceId-2",
                INTERNAL_PREFIX + TRACING_SPAN_ID, "spanId-2"));
        transactionalRepository.persist(record2);

        // then
        records = getAndCommitRecords();
        assertEquals(1, records.count());
        kafkaRecord = records.iterator().next();
        assertConsumedRecord(record2, "h2", eventSource, kafkaRecord);
        // verify spans: one for the transactional-outbox, one for the processing to Kafka
        assertEquals(4, tracer.getSpans().size());
        List<SimpleSpan> spans = tracer.getSpans().stream().toList();
        outboxSpan = spans.get(2);
        assertOutboxSpan(outboxSpan, "traceId-2", "spanId-2", record2);
        processingSpanContext = spans.get(3).context();
        assertProcessingSpan(processingSpanContext, "traceId-2", outboxSpan.context().spanId());
    }

    private List<String> keys(ConsumerRecords<String,byte[]> records) {
        return StreamSupport.stream(records.spliterator(), false).map(ConsumerRecord::key).toList();
    }

    @Test
    public void should_StartWhenKafkaIsNotAvailableAndProcessOutboxWhenKafkaIsAvailable() throws InterruptedException {
        // given
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);
        kafkaContainer.setConnectionCut(true);

        // when
        Duration processingInterval = Duration.ofMillis(50);
        String eventSource = "test";
        Map<String, Object> producerProps = producerProps(bootstrapServers);
        producerProps.put(REQUEST_TIMEOUT_MS_CONFIG, 5000);
        producerProps.put(DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        producerProps.put(MAX_BLOCK_MS_CONFIG, 5000);
        testee = new OutboxProcessor(repository, producerFactory(producerProps), processingInterval, DEFAULT_OUTBOX_LOCK_TIMEOUT, lockOwnerId(), eventSource, beanFactory);

        Thread.sleep(processingInterval.plusMillis(200).toMillis());
        kafkaContainer.setConnectionCut(false);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertEquals(1, records.count());
    }

    @Test
    public void should_ContinueProcessingAfterKafkaRestart() throws InterruptedException {
        // given
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        Duration processingInterval = Duration.ofMillis(50);
        String eventSource = "test";
        testee = new OutboxProcessor(repository, producerFactory(), processingInterval, DEFAULT_OUTBOX_LOCK_TIMEOUT, lockOwnerId(), eventSource, beanFactory);

        // when
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();

        // then
        assertEquals(1, records.count());

        // and when
        kafkaContainer.setConnectionCut(true);

        OutboxRecord record2 = newRecord(topic2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        Thread.sleep(processingInterval.plusMillis(200).toMillis());
        kafkaContainer.setConnectionCut(false);

        // then
        records = getAndCommitRecords();
        assertEquals(1, records.count());
        assertConsumedRecord(record2, "h2", eventSource, records.iterator().next());
    }

    @Test
    public void should_ContinueProcessingAfterDatabaseUnavailability() throws InterruptedException, IOException {
        // given
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        Duration processingInterval = Duration.ofMillis(50);
        Duration outboxLockTimeout = Duration.ofMillis(500);
        String eventSource = "test";
        testee = new OutboxProcessor(repository, producerFactory(), processingInterval, outboxLockTimeout, lockOwnerId(), eventSource, beanFactory);

        // when
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();

        // then
        assertEquals(1, records.count());

        // and when
        Timeout timeout = postgresProxy.toxics().timeout("TIMEOUT", DOWNSTREAM, 1L);
        Thread.sleep(processingInterval.multipliedBy(5).toMillis());
        timeout.remove();

        OutboxRecord record2 = newRecord(topic2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        // then
        records = getAndCommitRecords();
        assertEquals(1, records.count());
        assertConsumedRecord(record2, "h2", eventSource, records.iterator().next());
    }

    @Test
    public void should_ContinueProcessingAfterDbConnectionFailureInLockAcquisition() throws InterruptedException {
        // given
        Duration processingInterval = Duration.ofMillis(50);
        String eventSource = "test";

        AtomicBoolean failAcquireOrRefreshLock = new AtomicBoolean(false);
        CountDownLatch cdl = new CountDownLatch(1);

        OutboxLockRepository failingLockRepository = (OutboxLockRepository) beanFactory.initializeBean(
                new OutboxLockRepository(jdbcTemplate, transactionManager) {
                    @Override
                    public boolean acquireOrRefreshLock(String ownerId, Duration timeout) {
                        if (failAcquireOrRefreshLock.get()) {
                            cdl.countDown();
                            throw new RuntimeException("Simulated exception");
                        }
                        return super.acquireOrRefreshLock(ownerId, timeout);
                    }
                },
                "OutboxLockRepository"
        );
        AutowireCapableBeanFactory beanFactoryWrapper = new DefaultListableBeanFactory(beanFactory) {
            @Override
            @SuppressWarnings({"unchecked", "NullableProblems"})
            public <T> T getBean(Class<T> requiredType) throws BeansException {
                if (requiredType == OutboxLockRepository.class)
                    return (T) failingLockRepository;
                return beanFactory.getBean(requiredType);
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public Object initializeBean(Object existingBean, String beanName) throws BeansException {
                return beanFactory.initializeBean(existingBean, beanName);
            }
        };

        testee = new OutboxProcessor(repository, producerFactory(), processingInterval, DEFAULT_OUTBOX_LOCK_TIMEOUT, lockOwnerId(), eventSource, beanFactoryWrapper);

        // when
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertEquals(1, records.count());

        // and when
        failAcquireOrRefreshLock.set(true);

        OutboxRecord record2 = newRecord(topic2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        cdl.await();
        failAcquireOrRefreshLock.set(false);

        // then
        records = getAndCommitRecords();
        assertEquals(1, records.count());
    }

    @Test
    public void should_ContinueProcessingAfterDbConnectionFailureInPreventLockStealing() throws InterruptedException {
        // given
        Duration processingInterval = Duration.ofMillis(500);
        String eventSource = "test";

        AtomicBoolean failPreventLockStealing = new AtomicBoolean(false);
        CountDownLatch cdl = new CountDownLatch(1);

        OutboxLockRepository failingLockRepository = (OutboxLockRepository) beanFactory.initializeBean(
                new OutboxLockRepository(jdbcTemplate, transactionManager) {
                    @Override
                    public boolean preventLockStealing(String ownerId) {
                        if (failPreventLockStealing.get()) {
                            cdl.countDown();
                            throw new RuntimeException("Simulated exception");
                        }
                        return super.preventLockStealing(ownerId);
                    }
                },
                "OutboxLockRepository"
        );
        AutowireCapableBeanFactory beanFactoryWrapper = new DefaultListableBeanFactory(beanFactory) {
            @Override
            @SuppressWarnings({"unchecked", "NullableProblems"})
            public <T> T getBean(Class<T> requiredType) throws BeansException {
                if (requiredType == OutboxLockRepository.class)
                    return (T) failingLockRepository;
                return beanFactory.getBean(requiredType);
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public Object initializeBean(Object existingBean, String beanName) throws BeansException {
                return beanFactory.initializeBean(existingBean, beanName);
            }
        };

        testee = new OutboxProcessor(repository, producerFactory(), processingInterval, DEFAULT_OUTBOX_LOCK_TIMEOUT, lockOwnerId(), eventSource, beanFactoryWrapper);

        // when
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertEquals(1, records.count());

        // and when
        failPreventLockStealing.set(true);

        OutboxRecord record2 = newRecord(topic2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        cdl.await();
        failPreventLockStealing.set(false);

        // then
        records = getAndCommitRecords();
        assertEquals(1, records.count());
    }

    @Test
    public void should_CleanupOutdatedProcessedRecords() {
        // given
        String eventSource = "test";
        CleanupSettings cleanupSettings = CleanupSettings.builder()
                .interval(Duration.ofMillis(100))
                .retention(Duration.ofMillis(200))
                .build();
        testee = new OutboxProcessor(
                repository,
                producerFactory(),
                Duration.ofMillis(10),
                DEFAULT_OUTBOX_LOCK_TIMEOUT,
                lockOwnerId(),
                eventSource,
                cleanupSettings,
                null,
                beanFactory
        );

        // when
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);
        assertEquals(1, repository.getUnprocessedRecords(1).size());

        // then
        await().atMost(Duration.ofSeconds(5)).until(
                () -> repository.getUnprocessedRecords(1).isEmpty()
        );
        assertEquals(1, getAndCommitRecords(1).count());

        // and eventually
        await().atMost(Duration.ofSeconds(5)).until(
                () -> jdbcTemplate.queryForObject("select count(*) from outbox_kafka", Integer.class) == 0
        );
    }

    private void assertConsumedRecord(OutboxRecord outboxRecord, String headerKey, String sourceHeaderValue, ConsumerRecord<String, byte[]> kafkaRecord) {
        assertEquals(outboxRecord.getKey(), kafkaRecord.key());
        assertArrayEquals(outboxRecord.getValue(), kafkaRecord.value());
        assertArrayEquals(outboxRecord.getHeaders().get(headerKey).getBytes(), kafkaRecord.headers().lastHeader(headerKey).value());
        assertEquals(outboxRecord.getId().longValue(), toLong(kafkaRecord.headers().lastHeader(HEADERS_SEQUENCE_NAME).value()));
        assertArrayEquals(sourceHeaderValue.getBytes(), kafkaRecord.headers().lastHeader(HEADERS_SOURCE_NAME).value());
    }

    @Override
    public Consumer<String, byte[]> consumer() {
        if (consumer == null) {
            consumer = createConsumer(bootstrapServers);
            consumer.subscribe(Arrays.asList(topic1, topic2));
        }
        return consumer;
    }

}
