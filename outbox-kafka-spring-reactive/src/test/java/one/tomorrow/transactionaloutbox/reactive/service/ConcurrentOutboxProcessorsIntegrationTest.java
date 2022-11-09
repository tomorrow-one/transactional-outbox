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
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.stream.IntStream.range;
import static one.tomorrow.transactionaloutbox.reactive.KafkaTestSupport.*;
import static one.tomorrow.transactionaloutbox.reactive.ProxiedKafkaContainer.bootstrapServers;
import static one.tomorrow.transactionaloutbox.reactive.TestUtils.newRecord;

@FlywayTest
@SuppressWarnings({"unused"})
class ConcurrentOutboxProcessorsIntegrationTest extends AbstractIntegrationTest implements KafkaTestSupport {

    private static final String topic = "topicConcurrentTest";
    private static Consumer<String, byte[]> consumer;

    @Autowired
    private OutboxRepository repository;
    @Autowired
    private OutboxLockService lockService;

    private OutboxProcessor testee1;
    private OutboxProcessor testee2;

    @BeforeAll
    public static void beforeAll() {
        createTopic(bootstrapServers, topic);
    }

    @AfterAll
    public static void afterAll() {
        if (consumer != null)
            consumer.close();
    }

    @AfterEach
    public void afterTest() {
        testee1.close();
        testee2.close();
    }

    @Test
    void should_processRecordsOnceInOrder() {
        // given
        Duration lockTimeout = Duration.ofMillis(20); // very aggressive lock stealing
        Duration processingInterval = Duration.ZERO;
        String eventSource = "test";
        testee1 = new OutboxProcessor(repository, lockService, producerFactory(), processingInterval, lockTimeout, "processor1", eventSource);
        testee2 = new OutboxProcessor(repository, lockService, producerFactory(), processingInterval, lockTimeout, "processor2", eventSource);

        // when
        List<OutboxRecord> outboxRecords = range(0, 1000).mapToObj(i ->
                repository.save(newRecord(topic, "key1", "value" + i, Map.of("h", "v" + i))).block()
        ).collect(Collectors.toList());

        // then
        Iterator<ConsumerRecord<String, byte[]>> kafkaRecords = getAndCommitRecords(outboxRecords.size()).iterator();
        outboxRecords.forEach(outboxRecord ->
                assertConsumedRecord(outboxRecord, eventSource, kafkaRecords.next())
        );
    }

    @Override
    public Consumer<String, byte[]> consumer() {
        if (consumer == null) {
            consumer = createConsumer(bootstrapServers);
            consumer.subscribe(List.of(topic));
        }
        return consumer;
    }

}
