/**
 * Copyright 2023 Tomorrow GmbH @ https://tomorrow.one
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
import one.tomorrow.transactionaloutbox.ProxiedKafkaContainer;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Duration;
import java.util.List;

import static one.tomorrow.transactionaloutbox.IntegrationTestConfig.DEFAULT_OUTBOX_LOCK_TIMEOUT;
import static one.tomorrow.transactionaloutbox.KafkaTestSupport.createConsumer;
import static one.tomorrow.transactionaloutbox.KafkaTestSupport.producerProps;
import static one.tomorrow.transactionaloutbox.ProxiedKafkaContainer.bootstrapServers;
import static one.tomorrow.transactionaloutbox.service.SampleService.Topics.topic1;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {
        OutboxRecord.class,
        OutboxRepository.class,
        OutboxLock.class,
        OutboxLockRepository.class,
        OutboxService.class,
        SampleService.class,
        IntegrationTestConfig.class,
        OutboxUsageIntegrationTest.OutboxProcessorSetup.class
})
@TestExecutionListeners({
        DependencyInjectionTestExecutionListener.class,
        FlywayTestExecutionListener.class
})
@FlywayTest
@SuppressWarnings("unused")
public class OutboxUsageIntegrationTest implements KafkaTestSupport<String> {

    public static final ProxiedKafkaContainer kafkaContainer = ProxiedKafkaContainer.startProxiedKafka();
    private static Consumer<String, String> consumer;

    @Autowired
    private SampleService sampleService;
    @Autowired
    private ApplicationContext applicationContext;

    private static OutboxProcessor outboxProcessor;

    @Before
    public void setup() {
        // now activate the lazy processor
        if (outboxProcessor == null)
            outboxProcessor = applicationContext.getBean(OutboxProcessor.class);
    }

    @AfterClass
    public static void afterClass() {
        if (outboxProcessor != null)
            outboxProcessor.close();
        if (consumer != null)
            consumer.close();
    }

    @Test
    public void should_SaveEventForPublishing() {
        // given beans in ContextConfiguration and OutboxSetup below

        // when
        int id = 23;
        String name = "foo bar";
        sampleService.doSomething(id, name);

        // then
        ConsumerRecords<String, String> records = getAndCommitRecords();
        assertThat(records.count(), is(1));
        ConsumerRecord<String, String> kafkaRecord = records.iterator().next();
        assertEquals(id, Integer.parseInt(kafkaRecord.key()));
        assertEquals(name, kafkaRecord.value());
    }

    @Test
    public void should_SaveEventForPublishing_withAdditionalHeader() {
        // given beans in ContextConfiguration and OutboxSetup below

        // when
        int id = 24;
        String name = "foo bar baz";
        SampleService.Header additionalHeader = new SampleService.Header("key", "value");
        sampleService.doSomethingWithAdditionalHeaders(id, name, additionalHeader);

        // then
        ConsumerRecords<String, String> records = getAndCommitRecords();
        assertThat(records.count(), is(1));
        ConsumerRecord<String, String> kafkaRecord = records.iterator().next();

        assertEquals(id, Integer.parseInt(kafkaRecord.key()));
        assertEquals(name, kafkaRecord.value());

        Header foundHeader = kafkaRecord.headers().lastHeader("key");
        assertEquals(additionalHeader.getValue(), new String(foundHeader.value()));
    }

    @Override
    public Consumer<String, String> consumer() {
        if (consumer == null) {
            consumer = createConsumer(bootstrapServers, StringDeserializer.class);
            consumer.subscribe(List.of(topic1));
        }
        return consumer;
    }

    @Configuration
    public static class OutboxProcessorSetup {
        @Bean @Lazy // if not lazy, this is loaded before the FlywayTestExecutionListener got activated and created the needed tables
        public OutboxProcessor outboxProcessor(OutboxRepository repository, AutowireCapableBeanFactory beanFactory) {
            Duration processingInterval = Duration.ofMillis(50);
            String lockOwnerId = "processor";
            String eventSource = "test";
            return new OutboxProcessor(
                    repository,
                    new DefaultKafkaProducerFactory(producerProps(bootstrapServers)),
                    processingInterval,
                    DEFAULT_OUTBOX_LOCK_TIMEOUT,
                    lockOwnerId,
                    eventSource,
                    beanFactory
            );
        }
    }

}
