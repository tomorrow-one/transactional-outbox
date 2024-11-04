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
package one.tomorrow.transactionaloutbox.reactive.service;

import one.tomorrow.transactionaloutbox.reactive.AbstractIntegrationTest;
import one.tomorrow.transactionaloutbox.reactive.KafkaTestSupport;
import one.tomorrow.transactionaloutbox.commons.ProxiedContainerSupport;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.reactive.service.OutboxProcessor.CleanupSettings;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static one.tomorrow.transactionaloutbox.commons.CommonKafkaTestSupport.*;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.Longs.toLong;
import static one.tomorrow.transactionaloutbox.reactive.IntegrationTestConfig.DEFAULT_OUTBOX_LOCK_TIMEOUT;
import static one.tomorrow.transactionaloutbox.reactive.KafkaTestSupport.*;
import static one.tomorrow.transactionaloutbox.commons.ProxiedKafkaContainer.bootstrapServers;
import static one.tomorrow.transactionaloutbox.reactive.TestUtils.newRecord;
import static org.apache.kafka.clients.producer.ProducerConfig.*;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@FlywayTest
@SuppressWarnings({"unused", "ConstantConditions"})
class OutboxProcessorIntegrationTest extends AbstractIntegrationTest implements KafkaTestSupport {

    private static final String topic1 = "topicOPIT1";
    private static final String topic2 = "topicOPIT2";
    private static final AtomicInteger processorIdx = new AtomicInteger(0);

    private static Consumer<String, byte[]> consumer;

    @Autowired
    private OutboxRepository repository;
    @Autowired
    private OutboxLockService lockService;

    private OutboxProcessor testee;

    @DynamicPropertySource
    public static void setShortR2DBCPoolTimeouts(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.pool.max-create-connection-time", () -> "100ms");
        registry.add("spring.r2dbc.pool.max-acquire-time", () -> "25ms");
    }

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
    void should_processRecordsInOrder() {
        // given
        String eventSource = "test";
        testee = new OutboxProcessor(repository, lockService, producerFactory(), Duration.ofMillis(50), DEFAULT_OUTBOX_LOCK_TIMEOUT, lockOwnerId(), eventSource);

        // when
        List<OutboxRecord> outboxRecords = IntStream.range(1, 100).mapToObj(i ->
                repository.save(newRecord(topic1, "key", "value" + i)).block()
        ).toList();

        logger.info("Test created records");

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords(outboxRecords.size()); // await().atMost(5, SECONDS).until(() -> getAndCommitRecords(1), is(iterableWithSize(1)));
        assertThat(records.count(), is(outboxRecords.size()));
        Iterator<ConsumerRecord<String, byte[]>> kafkaRecordsIter = records.iterator();
        for (OutboxRecord outboxRecord : outboxRecords) {
            assertConsumedRecord(outboxRecord, eventSource, kafkaRecordsIter.next());
        }
    }

    @Test
    void should_startWhenKafkaIsNotAvailable_and_processOutboxWhenKafkaBecomesAvailable() {
        // given
        OutboxRecord record = repository.save(newRecord(topic1, "key1", "value1")).retry().block();
        kafkaContainer.setConnectionCut(true);

        // when
        Duration processingInterval = Duration.ofMillis(50);
        String eventSource = "test";
        testee = new OutboxProcessor(repository, lockService, producerFactory(), processingInterval, DEFAULT_OUTBOX_LOCK_TIMEOUT, lockOwnerId(), eventSource);

        sleep(processingInterval.plusMillis(200));
        kafkaContainer.setConnectionCut(false);

        // then
        ConsumerRecords<String, byte[]> kafkaRecords = getAndCommitRecords();
        assertThat(kafkaRecords.count(), is(1));
        assertConsumedRecord(record, eventSource, kafkaRecords.iterator().next());
    }

    @Test
    void should_processRecordsInOrder_whenKafkaIsTemporarilyNotAvailable() {
        // given
        String eventSource = "test";
        // producer props to let the producer actually fail on send() with our simulated timeouts
        Duration requestTimeout = Duration.ofMillis(10);
        Map<String, Object> producerProps = producerPropsWithShortTimeouts(requestTimeout);
        Duration processingInterval = Duration.ofMillis(50);
        int batchSize = 100;
        testee = new OutboxProcessor(repository, lockService, producerFactory(producerProps), processingInterval, DEFAULT_OUTBOX_LOCK_TIMEOUT, lockOwnerId(), eventSource, batchSize, null);

        // when
        int numRecords = 500;
        List<Mono<OutboxRecord>> outboxRecordMonos = IntStream.rangeClosed(1, numRecords).mapToObj(i -> {
            // - use the same key so that even if the kafka setup / number of partitions is changed the events still are on the same partition
            // - The Mono has to be cached, so that it can be consumed twice
            return repository.save(newRecord(topic1, "key", "value" + i)).retry().cache();
        }).collect(toList());

        // toggle connection as long as there are unprocessed records
        // - wait until the first element is saved, otherwise the first time the first record might not yet be saved
        outboxRecordMonos.stream().findFirst().orElseThrow().doOnNext(savedRecord ->
                toggleConnectionWhileNonEmpty(repository.getUnprocessedRecords(1), requestTimeout.multipliedBy(3), kafkaContainer)
        ).subscribe();

        // then
        List<OutboxRecord> outboxRecords = getSortedById(outboxRecordMonos);
        Collection<ConsumerRecord<String, byte[]>> kafkaRecords = consumeAndDeduplicateRecords(outboxRecords.size(), Duration.ofSeconds(30));

        List<Long> outboxRecordIds = outboxRecords.stream().map(OutboxRecord::getId).toList();
        List<Long> kafkaRecordIds = kafkaRecords.stream().map(rec -> toLong(rec.headers().lastHeader(HEADERS_SEQUENCE_NAME).value())).toList();
        assertEquals(outboxRecordIds, kafkaRecordIds, "OutboxRecord ids and Kafka record ids do not match");

        Iterator<ConsumerRecord<String, byte[]>> kafkaRecordsIter = kafkaRecords.iterator();
        for (OutboxRecord outboxRecord : outboxRecords) {
            assertConsumedRecord(outboxRecord, eventSource, kafkaRecordsIter.next());
        }
    }

    @Test
    void should_processRecordsInOrder_whenDatabaseIsTemporarilyNotAvailable() {
        // given
        String eventSource = "test";
        Duration processingInterval = Duration.ofMillis(2);
        int batchSize = 5; // use a smaller batch size so that batch management (locking etc) is likely to happen during a cut connection
        testee = new OutboxProcessor(repository, lockService, producerFactory(), processingInterval, DEFAULT_OUTBOX_LOCK_TIMEOUT, lockOwnerId(), eventSource, batchSize, null);

        // when
        int numRecords = 500;
        List<Mono<OutboxRecord>> outboxRecordMonos = IntStream.rangeClosed(1, numRecords).mapToObj(i -> {
            // - use the same key so that even if the kafka setup / number of partitions is changed the events still are on the same partition
            // - The Mono has to be cached, so that it can be consumed twice
            return repository.save(newRecord(topic1, "key", "value" + i)).retry().cache();
        }).collect(toList());

        // toggle connection as long as there are unprocessed records
        // - wait until the first element is saved, otherwise the first time the first record might not yet be saved
        outboxRecordMonos.stream().findFirst().orElseThrow().doOnNext(savedRecord ->
                toggleConnectionWhileNonEmpty(repository.getUnprocessedRecords(1), processingInterval, postgresqlContainer)
        ).subscribe();

        // then
        List<OutboxRecord> outboxRecords = getSortedById(outboxRecordMonos);
        Iterator<ConsumerRecord<String, byte[]>> kafkaRecordsIter = consumeAndDeduplicateRecords(outboxRecords.size(), Duration.ofSeconds(30))
                .iterator();
        for (OutboxRecord outboxRecord : outboxRecords) {
            assertConsumedRecord(outboxRecord, eventSource, kafkaRecordsIter.next());
        }
    }

    @Test
    void should_cleanupOutdatedProcessedRecords() {
        // given
        Duration processingInterval = Duration.ofMillis(50);
        String eventSource = "test";
        CleanupSettings cleanupSettings = CleanupSettings.builder()
                .interval(Duration.ofMillis(100))
                .retention(Duration.ofMillis(200))
                .build();
        testee = new OutboxProcessor(repository,
                lockService,
                producerFactory(),
                processingInterval,
                DEFAULT_OUTBOX_LOCK_TIMEOUT,
                lockOwnerId(),
                eventSource,
                cleanupSettings);

        // when
        OutboxRecord outboxRecord = repository.save(newRecord(topic1, "key1", "value1")).block();

        // then
        assertThat(getAndCommitRecords(1).count(), is(1));
        await().atMost(Duration.ofSeconds(5)).until(
                () -> repository.getUnprocessedRecords(100).collectList().block().isEmpty()
        );

        // and eventually
        await().atMost(Duration.ofSeconds(5)).until(
                () -> repository.findAll().collectList().block().isEmpty()
        );
    }

    private List<OutboxRecord> getSortedById(List<Mono<OutboxRecord>> outboxRecordMonos) {
        return outboxRecordMonos.stream()
                .map(Mono::block)
                .sorted(comparing(OutboxRecord::getId))
                .collect(toList());
    }

    private void toggleConnectionWhileNonEmpty(Flux<?> records, Duration toggleInterval, ProxiedContainerSupport proxiedContainer) {
        AtomicBoolean connected = new AtomicBoolean(true);
        records.collectList()
                .retry()
                // cut connection
                .doOnNext(unused -> toggleConnection(connected, proxiedContainer))
                .delayElement(toggleInterval)
                // restore connection (needed so that the next round/check can work at all)
                .doOnNext(unused -> toggleConnection(connected, proxiedContainer))
                .delayElement(toggleInterval)
                .repeatWhen(elementsReturned -> elementsReturned.flatMap(x -> x > 0 ? Mono.just(true) : Mono.empty()))
                .subscribe();
    }

    private void toggleConnection(AtomicBoolean connected, ProxiedContainerSupport proxiedContainer) {
        proxiedContainer.setConnectionCut(connected.getAndSet(!connected.get()));
    }

    private Collection<ConsumerRecord<String, byte[]>> consumeAndDeduplicateRecords(int minRecords, Duration timeout) {
        LinkedHashMap<Long, ConsumerRecord<String, byte[]>> kafkaRecordBySeqNr = new LinkedHashMap<>();
        return await().atMost(timeout).until(
                () -> consumeAndDeduplicateRecords(kafkaRecordBySeqNr),
                recordsBySeqNr -> recordsBySeqNr.size() >= minRecords
        ).values();
    }

    private LinkedHashMap<Long, ConsumerRecord<String, byte[]>> consumeAndDeduplicateRecords(LinkedHashMap<Long, ConsumerRecord<String, byte[]>> kafkaRecordBySeqNr) {
        ConsumerRecords<String, byte[]> recordsToAdd = getAndCommitRecords();
        for (ConsumerRecord<String, byte[]> record : recordsToAdd) {
            Long seqNr = toLong(record.headers().lastHeader(HEADERS_SEQUENCE_NAME).value());
            if (kafkaRecordBySeqNr.containsKey(seqNr))
                logger.info("Have duplicate with seqNr {} (offset 1: {}, offset 2: {})", seqNr, kafkaRecordBySeqNr.get(seqNr).offset(), record.offset());
            kafkaRecordBySeqNr.put(seqNr, record);
        }
        return kafkaRecordBySeqNr;
    }

    private Collection<ConsumerRecord<String, byte[]>> deduplicate(ConsumerRecords<String, byte[]> records) {
        LinkedHashMap<Long, ConsumerRecord<String, byte[]>> kafkaRecordBySeqNr = new LinkedHashMap<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            long seqNr = toLong(record.headers().lastHeader(HEADERS_SEQUENCE_NAME).value());
            kafkaRecordBySeqNr.put(seqNr, record);
        }
        return kafkaRecordBySeqNr.values();
    }

    private Map<String, Object> producerPropsWithShortTimeouts(Duration requestTimeout) {
        Map<String, Object> producerProps = producerProps(bootstrapServers);
        producerProps.put(REQUEST_TIMEOUT_MS_CONFIG, (int) requestTimeout.toMillis());
        producerProps.put(MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        return producerProps;
    }

    @Override
    public Consumer<String, byte[]> consumer() {
        if (consumer == null) {
            consumer = createConsumer(bootstrapServers);
            consumer.subscribe(Arrays.asList(topic1, topic2));
        }
        return consumer;
    }

    private void sleep(Duration timeToSleep) {
        try {
            Thread.sleep(timeToSleep.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

}
