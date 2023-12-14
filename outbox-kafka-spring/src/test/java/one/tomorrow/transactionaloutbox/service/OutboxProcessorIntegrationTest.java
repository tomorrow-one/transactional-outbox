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
import one.tomorrow.transactionaloutbox.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.KafkaTestSupport;
import one.tomorrow.transactionaloutbox.ProxiedKafkaContainer;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.After;
import org.junit.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.runner.RunWith;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
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
import static one.tomorrow.transactionaloutbox.ProxiedKafkaContainer.bootstrapServers;
import static one.tomorrow.transactionaloutbox.ProxiedKafkaContainer.kafkaProxy;
import static one.tomorrow.transactionaloutbox.ProxiedPostgreSQLContainer.postgresProxy;
import static one.tomorrow.transactionaloutbox.TestUtils.newHeaders;
import static one.tomorrow.transactionaloutbox.TestUtils.newRecord;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SOURCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.Longs.toLong;
import static org.apache.kafka.clients.producer.ProducerConfig.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {
        OutboxRecord.class,
        OutboxRepository.class,
        TransactionalOutboxRepository.class,
        OutboxLock.class,
        OutboxLockRepository.class,
        IntegrationTestConfig.class
})
@TestExecutionListeners({
        DependencyInjectionTestExecutionListener.class,
        FlywayTestExecutionListener.class
})
@FlywayTest
@SuppressWarnings("unused")
public class OutboxProcessorIntegrationTest implements KafkaTestSupport<byte[]> {

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

    @After
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
        testee = new OutboxProcessor(repository, producerFactory(), Duration.ofMillis(50), DEFAULT_OUTBOX_LOCK_TIMEOUT, lockOwnerId(), eventSource, beanFactory);

        // when
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertEquals("Have records with keys: " + keys(records), 1, records.count());
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

    private List<String> keys(ConsumerRecords<String,byte[]> records) {
        return StreamSupport.stream(records.spliterator(), false).map(ConsumerRecord::key).toList();
    }

    @Test
    public void should_StartWhenKafkaIsNotAvailableAndProcessOutboxWhenKafkaIsAvailable() throws InterruptedException {
        // given
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);
        kafkaProxy.setConnectionCut(true);

        // when
        Duration processingInterval = Duration.ofMillis(50);
        String eventSource = "test";
        Map<String, Object> producerProps = producerProps(bootstrapServers);
        producerProps.put(REQUEST_TIMEOUT_MS_CONFIG, 5000);
        producerProps.put(DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        producerProps.put(MAX_BLOCK_MS_CONFIG, 5000);
        testee = new OutboxProcessor(repository, producerFactory(producerProps), processingInterval, DEFAULT_OUTBOX_LOCK_TIMEOUT, lockOwnerId(), eventSource, beanFactory);

        Thread.sleep(processingInterval.plusMillis(200).toMillis());
        kafkaProxy.setConnectionCut(false);

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
        kafkaProxy.setConnectionCut(true);

        OutboxRecord record2 = newRecord(topic2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        Thread.sleep(processingInterval.plusMillis(200).toMillis());
        kafkaProxy.setConnectionCut(false);

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

        OutboxLockRepository failingLockRepository = (OutboxLockRepository) beanFactory.applyBeanPostProcessorsAfterInitialization(
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
            public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName) throws BeansException {
                return beanFactory.applyBeanPostProcessorsAfterInitialization(existingBean, beanName);
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

        OutboxLockRepository failingLockRepository = (OutboxLockRepository) beanFactory.applyBeanPostProcessorsAfterInitialization(
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
            public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName) throws BeansException {
                return beanFactory.applyBeanPostProcessorsAfterInitialization(existingBean, beanName);
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
