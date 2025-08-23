/**
 * Copyright 2025 Tomorrow GmbH @ https://tomorrow.one
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
package one.tomorrow.transactionaloutbox.quarkus.deployment;

import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.service.DefaultKafkaProducerFactory;
import one.tomorrow.transactionaloutbox.service.OutboxLockService;
import one.tomorrow.transactionaloutbox.service.OutboxProcessor;
import one.tomorrow.transactionaloutbox.service.OutboxService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TransactionalOutboxExtensionTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withConfigurationResource("application-test.properties")
            .setArchiveProducer(() ->
                    ShrinkWrap.create(JavaArchive.class)
                            .addClasses(TestApp.class)
                            .addClasses(OutboxLockRepository.class)
                            .addClasses(OutboxLockService.class)
                            .addClasses(TransactionalOutboxConfig.class)
                            .addClasses(OutboxProcessor.class)
                            .addClasses(OutboxProcessor.KafkaProducerFactory.class)
                            .addClasses(DefaultKafkaProducerFactory.class)
                            .addAsResource("application-test.properties")
                            .addAsResource("db/migration/V1__add-outbox-tables.sql")
            );

    private final String topic = "deployment-topic-" + System.currentTimeMillis();

    @Inject
    OutboxService outboxService;

    @Inject
    @Identifier("default-kafka-broker")
    Map<String, Object> kafkaConfig;

    @Test
    void testTransactionalOutbox() {
        // given / when
        OutboxRecord outboxRecord = createOutboxRecord();
        assertNotNull(outboxRecord.getId());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig())) {
            consumer.subscribe(List.of(topic));

            // then
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertEquals(1, records.count());
            ConsumerRecord<String, String> consumerRecord = records.iterator().next();
            assertEquals(outboxRecord.getKey(), consumerRecord.key());
            assertEquals(new String(outboxRecord.getValue()), consumerRecord.value());
        }
    }

    @Transactional
    OutboxRecord createOutboxRecord() {
        return outboxService.saveForPublishing(topic, "k1", "value".getBytes());
    }

    private @NotNull Map<String, Object> consumerConfig() {
        Map<String, Object> consumerProps = new HashMap<>(kafkaConfig);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "testDeploymentConsumer");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return consumerProps;
    }

}
