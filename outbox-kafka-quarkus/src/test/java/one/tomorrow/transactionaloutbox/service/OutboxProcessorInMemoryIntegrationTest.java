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

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.tuples.Tuple2;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.ProxiedKafkaContainer;
import one.tomorrow.transactionaloutbox.ProxiedPostgreSQLContainer;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.publisher.EmitterMessagePublisher;
import one.tomorrow.transactionaloutbox.publisher.MessagePublisherFactory;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.tracing.TracingService;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.SECONDS;
import static one.tomorrow.transactionaloutbox.ProxiedPostgreSQLContainer.startProxiedPostgres;
import static one.tomorrow.transactionaloutbox.TestUtils.newHeaders;
import static one.tomorrow.transactionaloutbox.TestUtils.newRecord;
import static one.tomorrow.transactionaloutbox.config.TestTransactionalOutboxConfig.createConfig;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(OutboxProcessorInMemoryIntegrationTest.class)
public class OutboxProcessorInMemoryIntegrationTest implements QuarkusTestProfile {

    private static final String TOPIC_1 = "topicOPIMIT1";
    private static final String TOPIC_2 = "topicOPIMIT2";
    private static final String LOCK_OWNER_ID = "processorInMemIT";

    public static final ProxiedPostgreSQLContainer postgresqlContainer = startProxiedPostgres();
    static {
        ProxiedKafkaContainer.stopProxiedKafka();
    }

    @ApplicationScoped
    public static class TestEmitterResolver implements EmitterMessagePublisher.EmitterResolver {

        @Channel("channel1")
        MutinyEmitter<byte[]> emitter1;
        @Channel("channel2")
        MutinyEmitter<byte[]> emitter2;

        @Override
        public MutinyEmitter<byte[]> resolveBy(String topic) {
            if (TOPIC_1.equals(topic))
                return emitter1;
            if (TOPIC_2.equals(topic))
                return emitter2;
            return null;
        }
    }

    @Inject
    EntityManager entityManager;
    @Inject
    OutboxRepository repository;
    @Inject
    OutboxLockService lockService;
    @Inject
    TestOutboxRepository transactionalRepository;
    @Inject
    TracingService tracingService;
    @Inject
    MessagePublisherFactory publisherFactory;

    @Inject
    @Connector("smallrye-in-memory")
    InMemoryConnector inMemoryConnector;

    private InMemorySink<byte[]> channel1Sink;
    private InMemorySink<byte[]> channel2Sink;

    private OutboxProcessor testee;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                // db config
                "quarkus.datasource.devservices.enabled", "false",
                "quarkus.datasource.jdbc.url", postgresqlContainer.getJdbcUrl(),
                "quarkus.datasource.username", postgresqlContainer.getUsername(),
                "quarkus.datasource.password", postgresqlContainer.getPassword(),
                // kafka config
                "quarkus.kafka.devservices.enabled", "false",
                "kafka.bootstrap.servers", "", // ensure that this is not set
                // outgoing channel config
                "mp.messaging.outgoing.channel1.connector", "smallrye-in-memory",
                "mp.messaging.outgoing.channel1.topic", TOPIC_1,
                "mp.messaging.outgoing.channel2.connector", "smallrye-in-memory",
                "mp.messaging.outgoing.channel2.topic", TOPIC_2
        );
    }

    @BeforeEach
    void setUp() {
        channel1Sink = inMemoryConnector.sink("channel1");
        channel2Sink = inMemoryConnector.sink("channel2");
    }

    @BeforeEach
    @Transactional
    void cleanUp() {
        OutboxLock outboxLock = entityManager.find(OutboxLock.class, OutboxLock.OUTBOX_LOCK_ID);
        if (outboxLock != null && !outboxLock.getOwnerId().equals(LOCK_OWNER_ID)) {
            entityManager.remove(outboxLock);
        }
        entityManager.createQuery("DELETE FROM OutboxRecord").executeUpdate();
    }

    @AfterEach
    void afterTest() {
        testee.close();
    }

    @Test
    void should_ProcessNewRecords() {
        // given
        TransactionalOutboxConfig config = createConfig(
                Duration.ofMillis(50),
                Duration.ofMillis(200),
                LOCK_OWNER_ID,
                "eventSource"
        );
        testee = new OutboxProcessor(config, repository, publisherFactory, lockService, tracingService);

        // when
        OutboxRecord record1 = newRecord(TOPIC_1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        // then
        Tuple2<Object, byte[]> kv = waitForNextMessage(channel1Sink);
        assertEquals(record1.getKey(), kv.getItem1());
        assertEquals(new String(record1.getValue()), new String(kv.getItem2()));

        // and when
        OutboxRecord record2 = newRecord(TOPIC_2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        // then
        kv = waitForNextMessage(channel2Sink);
        assertEquals(record2.getKey(), kv.getItem1());
        assertEquals(new String(record2.getValue()), new String(kv.getItem2()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <K, V> Tuple2<K, V> waitForNextMessage(InMemorySink<V> sink) {
        List<? extends Message<V>> received =
                await().atMost(10, SECONDS).until(sink::received, hasSize(1));
        Message<V> message = received.get(0);
        Optional<OutgoingKafkaRecordMetadata> metadata =
                message.getMetadata(OutgoingKafkaRecordMetadata.class);
        K key = (K) metadata.orElseThrow().getKey();
        V payload = message.getPayload();

        sink.clear();

        return Tuple2.of(key, payload);
    }

}
