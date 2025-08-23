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

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.KafkaTestUtils;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Map;

import static one.tomorrow.transactionaloutbox.KafkaTestUtils.*;
import static one.tomorrow.transactionaloutbox.service.SampleService.Topics.topic1;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(OutboxUsageIntegrationTest.class)
public class OutboxUsageIntegrationTest implements QuarkusTestProfile {

    private static Consumer<String, String> consumer;

    @Inject
    SampleService sampleService;

    @Inject
    EntityManager entityManager;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "one.tomorrow.transactional-outbox.enabled", "true", // default (only needed here because of our application.property)
                "one.tomorrow.transactional-outbox.processing-interval", "PT0.05S",
                "one.tomorrow.transactional-outbox.lock-owner-id", "processor",
                "one.tomorrow.transactional-outbox.lock-timeout", "PT0.2S",
                "one.tomorrow.transactional-outbox.event-source", "test"
        );
    }

    @BeforeAll
    static void beforeAll() {
        createTopic(bootstrapServers(), topic1);
        consumer = setupConsumer("testGroup", false, StringDeserializer.class, StringDeserializer.class, topic1);
    }

    @AfterAll
    static void afterClass() {
        consumer.close();
    }

    @BeforeEach
    @AfterEach
    @Transactional
    void cleanUp() {
        entityManager.createQuery("DELETE FROM OutboxRecord").executeUpdate();
    }

    @Test
    void should_SaveEventForPublishing() {

        // when
        int id = 23;
        String name = "foo bar";
        sampleService.doSomething(id, name);

        // then
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));
        assertEquals(1, records.count());
        ConsumerRecord<String, String> kafkaRecord = records.iterator().next();
        assertEquals(id, Integer.parseInt(kafkaRecord.key()));
        assertEquals(name, kafkaRecord.value());
    }

    @Test
    void should_SaveEventForPublishing_withAdditionalHeader() {

        // when
        int id = 24;
        String name = "foo bar baz";
        SampleService.Header additionalHeader = new SampleService.Header("key", "value");
        sampleService.doSomethingWithAdditionalHeaders(id, name, additionalHeader);

        // then
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));
        assertEquals(1, records.count());
        ConsumerRecord<String, String> kafkaRecord = records.iterator().next();

        assertEquals(id, Integer.parseInt(kafkaRecord.key()));
        assertEquals(name, kafkaRecord.value());

        Header foundHeader = kafkaRecord.headers().lastHeader("key");
        assertEquals(additionalHeader.getValue(), new String(foundHeader.value()));
    }

}
