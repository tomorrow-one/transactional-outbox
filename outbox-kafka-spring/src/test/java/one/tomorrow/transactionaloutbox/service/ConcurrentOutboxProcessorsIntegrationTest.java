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

import one.tomorrow.transactionaloutbox.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.KafkaTestSupport;
import one.tomorrow.transactionaloutbox.commons.ProxiedKafkaContainer;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;

import static java.util.stream.IntStream.range;
import static one.tomorrow.transactionaloutbox.commons.CommonKafkaTestSupport.*;
import static one.tomorrow.transactionaloutbox.KafkaTestSupport.*;
import static one.tomorrow.transactionaloutbox.commons.ProxiedKafkaContainer.bootstrapServers;
import static one.tomorrow.transactionaloutbox.TestUtils.newHeaders;
import static one.tomorrow.transactionaloutbox.TestUtils.newRecord;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {
        OutboxRecord.class,
        OutboxRepository.class,
        OutboxLockRepository.class,
        TransactionalOutboxRepository.class,
        IntegrationTestConfig.class
})
@TestExecutionListeners({
        DependencyInjectionTestExecutionListener.class,
        FlywayTestExecutionListener.class
})
@FlywayTest
@SuppressWarnings("unused")
public class ConcurrentOutboxProcessorsIntegrationTest implements KafkaTestSupport<byte[]> {

    public static final ProxiedKafkaContainer kafkaContainer = ProxiedKafkaContainer.startProxiedKafka();
    private static final String topic = "topicConcurrentTest";
    private static Consumer<String, byte[]> consumer;

    @Autowired
    private OutboxRepository repository;
    @Autowired
    private TransactionalOutboxRepository transactionalRepository;
    @Autowired
    private OutboxLockRepository lockRepository;
    @Autowired
    private AutowireCapableBeanFactory beanFactory;

    private OutboxProcessor testee1;
    private OutboxProcessor testee2;

    @BeforeAll
    public static void beforeAll() {
        createTopic(bootstrapServers, topic);
    }

    @AfterClass
    public static void afterClass() {
        if (consumer != null)
            consumer.close();
    }

    @After
    public void afterTest() {
        testee1.close();
        testee2.close();
    }

    @Test
    public void should_ProcessRecordsOnceInOrder() {
        // given
        Duration lockTimeout = Duration.ofMillis(20); // very aggressive lock stealing
        Duration processingInterval = Duration.ZERO;
        String eventSource = "test";
        testee1 = new OutboxProcessor(repository, producerFactory(), processingInterval, lockTimeout, "processor1", eventSource, beanFactory);
        testee2 = new OutboxProcessor(repository, producerFactory(), processingInterval, lockTimeout, "processor2", eventSource, beanFactory);

        // when
        List<OutboxRecord> outboxRecords = range(0, 1000).mapToObj(
                i -> newRecord(topic, "key1", "value" + i, newHeaders("h", "v" + i))
        ).toList();
        outboxRecords.forEach(transactionalRepository::persist);

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
