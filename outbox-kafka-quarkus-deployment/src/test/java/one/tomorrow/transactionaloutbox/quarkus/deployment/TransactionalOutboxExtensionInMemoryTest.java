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
import io.smallrye.mutiny.tuples.Tuple2;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.publisher.*;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.service.OutboxLockService;
import one.tomorrow.transactionaloutbox.service.OutboxProcessor;
import one.tomorrow.transactionaloutbox.service.OutboxService;
import one.tomorrow.transactionaloutbox.tracing.NoopTracingService;
import one.tomorrow.transactionaloutbox.tracing.NoopTracingServiceProducer;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TransactionalOutboxExtensionInMemoryTest {

    private static final String TOPIC = "deployment-inmem-topic";

    public static class TestEmitterResolver implements EmitterMessagePublisher.EmitterResolver {

        @Channel("testTopic")
        MutinyEmitter<byte[]> testTopicEmitter;

        @Override
        public MutinyEmitter<byte[]> resolveBy(String topic) {
            if (TOPIC.equals(topic))
                return testTopicEmitter;

            return null;
        }
    }

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withConfigurationResource("application-test.properties")
            .setArchiveProducer(() ->
                    ShrinkWrap.create(JavaArchive.class)
                            .addClasses(TestApp.class)
                            .addClasses(OutboxLockRepository.class)
                            .addClasses(OutboxLockService.class)
                            .addClasses(TransactionalOutboxConfig.class)
                            .addClasses(TransactionalOutboxConfig.CleanupConfig.class)
                            .addClasses(OutboxProcessor.class)
                            .addClasses(PublisherConfig.class)
                            .addClasses(MessagePublisher.class)
                            .addClasses(MessagePublisherFactory.class)
                            .addClasses(TestEmitterResolver.class)
                            .addClasses(EmitterMessagePublisher.class)
                            .addClasses(EmitterMessagePublisherFactory.class)
                            .addClasses(KafkaProducerMessagePublisher.class)
                            .addClasses(KafkaProducerMessagePublisherFactory.class)
                            .addClasses(KafkaProducerMessagePublisherFactory.KafkaProducerFactory.class)
                            .addClasses(DefaultKafkaProducerFactory.class)
                            .addClasses(NoopTracingService.class)
                            .addClasses(NoopTracingServiceProducer.class)
                            .addAsResource("application-test.properties")
                            .addAsResource("db/migration/V1__add-outbox-tables.sql")
            )
            .overrideConfigKey("quarkus.kafka.devservices.enabled", "false")
            // original
            .overrideConfigKey("mp.messaging.outgoing.testTopic.connector", "smallrye-in-memory") // in production "smallrye-kafka"
            .overrideConfigKey("mp.messaging.outgoing.testTopic.topic", TOPIC);

    @Inject
    EntityManager entityManager;

    @Inject
    OutboxService outboxService;

    @Inject
    @Connector("smallrye-in-memory")
    InMemoryConnector inMemoryConnector;

    private InMemorySink<byte[]> testTopicSink;

    @BeforeEach
    void setUp() {
        testTopicSink = inMemoryConnector.sink("testTopic");
    }

    @BeforeEach
    @AfterEach
    @Transactional
    void cleanUp() {
        entityManager.createQuery("DELETE FROM OutboxRecord").executeUpdate();
    }

    @Test
    void testTransactionalOutbox() {
        // given / when
        OutboxRecord outboxRecord = createOutboxRecord();
        assertNotNull(outboxRecord.getId());
        // then
        Tuple2<String, byte[]> keyValue = waitForNextMessage(testTopicSink);
        assertEquals(outboxRecord.getKey(), keyValue.getItem1());
        assertEquals(new String(outboxRecord.getValue()), new String(keyValue.getItem2()));
    }

    @Transactional
    OutboxRecord createOutboxRecord() {
        return outboxService.saveForPublishing(TOPIC, "k1", "value".getBytes());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <K, V> Tuple2<K, V> waitForNextMessage(InMemorySink<V> sink) {
        List<? extends Message<V>> received =
                await().atMost(5, SECONDS).until(sink::received, hasSize(1));
        Message<V> message = received.get(0);
        Optional<OutgoingKafkaRecordMetadata> metadata =
                message.getMetadata(OutgoingKafkaRecordMetadata.class);
        K key = (K) metadata.orElseThrow().getKey();
        V payload = message.getPayload();

        sink.clear();

        return Tuple2.of(key, payload);
    }

}
