package one.tomorrow.transactionaloutbox.service;

import one.tomorrow.transactionaloutbox.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.LockRepository;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import kafka.server.KafkaConfig$;
import kafka.server.KafkaServer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.hibernate.SessionFactory;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.rule.EmbeddedKafkaRule;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Duration;
import java.util.Map;

import static one.tomorrow.transactionaloutbox.TestUtils.newHeaders;
import static one.tomorrow.transactionaloutbox.TestUtils.newRecord;
import static one.tomorrow.transactionaloutbox.service.Numbers.toLong;
import static one.tomorrow.kafka.core.KafkaConstants.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.kafka.core.KafkaConstants.HEADERS_SOURCE_NAME;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.springframework.kafka.test.utils.KafkaTestUtils.producerProps;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {
        OutboxRecord.class,
        OutboxRepository.class,
        TransactionalOutboxRepository.class,
        OutboxLock.class,
        LockRepository.class,
        LockService.class,
        IntegrationTestConfig.class
})
@TestExecutionListeners({
        DependencyInjectionTestExecutionListener.class,
        FlywayTestExecutionListener.class
})
@FlywayTest
@SuppressWarnings("unused")
public class OutboxProcessorIntegrationTest {

    private static final String topic1 = "topicOPIT1";
    private static final String topic2 = "topicOPIT2";
    @Rule
    public EmbeddedKafkaRule kafkaRule = new EmbeddedKafkaRule(1, true, 5, topic1, topic2)
            .brokerProperty(KafkaConfig$.MODULE$.HostNameProp(), "127.0.0.1")
            .kafkaPorts(34568);
    private Consumer<String, byte[]> consumer;

    @Autowired
    private SessionFactory sessionFactory;
    @Autowired
    private OutboxRepository repository;
    @Autowired
    private TransactionalOutboxRepository transactionalRepository;
    @Autowired
    private LockService lockService;

    private OutboxProcessor testee;

    @After
    public void afterTest() {
        testee.close();
        if (consumer != null)
            consumer.close();
    }

    @Test
    public void should_ProcessNewRecords() {
        // given
        String eventSource = "test";
        testee = new OutboxProcessor(repository, producerFactory(), Duration.ofMillis(50), lockService, "processor", eventSource);

        // when
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertThat(records.count(), is(1));
        ConsumerRecord<String, byte[]> kafkaRecord = records.iterator().next();
        assertConsumedRecord(record1, "h1", eventSource, kafkaRecord);

        // and when
        OutboxRecord record2 = newRecord(topic2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        // then
        records = getAndCommitRecords();
        assertThat(records.count(), is(1));
        kafkaRecord = records.iterator().next();
        assertConsumedRecord(record2, "h2", eventSource, kafkaRecord);
    }

    private ConsumerRecords<String, byte[]> getAndCommitRecords() {
        ConsumerRecords<String, byte[]> records = KafkaTestUtils.getRecords(consumer(), 10_000);
        consumer().commitSync();
        return records;
    }

    @Test
    public void should_StartWhenKafkaIsNotAvailableAndProcessOutboxWhenKafkaIsAvailable() throws InterruptedException {
        // given
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        shutdownKafkaServers();

        // when
        Duration processingInterval = Duration.ofMillis(50);
        String eventSource = "test";
        testee = new OutboxProcessor(repository, producerFactory(), processingInterval, lockService, "processor", eventSource);

        Thread.sleep(processingInterval.plusMillis(200).toMillis());
        startKafkaServers();

        // then
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();
        assertThat(records.count(), is(1));
    }

    @Test
    public void should_ContinueProcessingAfterKafkaRestart() throws InterruptedException {
        // given
        OutboxRecord record1 = newRecord(topic1, "key1", "value1", newHeaders("h1", "v1"));
        transactionalRepository.persist(record1);

        Duration processingInterval = Duration.ofMillis(50);
        String eventSource = "test";
        testee = new OutboxProcessor(repository, producerFactory(), processingInterval, lockService, "processor", eventSource);

        // when
        ConsumerRecords<String, byte[]> records = getAndCommitRecords();

        // then
        assertThat(records.count(), is(1));

        // and when
        shutdownKafkaServers();

        OutboxRecord record2 = newRecord(topic2, "key2", "value2", newHeaders("h2", "v2"));
        transactionalRepository.persist(record2);

        Thread.sleep(processingInterval.plusMillis(200).toMillis());
        startKafkaServers();

        // then
        records = getAndCommitRecords();
        assertThat(records.count(), is(1));
        assertConsumedRecord(record2, "h2", eventSource, records.iterator().next());
    }

    private void assertConsumedRecord(OutboxRecord outboxRecord, String headerKey, String sourceHeaderValue, ConsumerRecord<String, byte[]> kafkaRecord) {
        assertEquals(outboxRecord.getKey(), kafkaRecord.key());
        assertArrayEquals(outboxRecord.getValue(), kafkaRecord.value());
        assertArrayEquals(outboxRecord.getHeaders().get(headerKey).getBytes(), kafkaRecord.headers().lastHeader(headerKey).value());
        assertEquals(outboxRecord.getId().longValue(), toLong(kafkaRecord.headers().lastHeader(HEADERS_SEQUENCE_NAME).value()));
        assertArrayEquals(sourceHeaderValue.getBytes(), kafkaRecord.headers().lastHeader(HEADERS_SOURCE_NAME).value());
    }

    private DefaultKafkaProducerFactory producerFactory() {
        return new DefaultKafkaProducerFactory(producerProps(embeddedKafka()));
    }

    private EmbeddedKafkaBroker embeddedKafka() {
        return kafkaRule.getEmbeddedKafka();
    }

    private void startKafkaServers() {
        for (KafkaServer kafkaServer : embeddedKafka().getKafkaServers()) {
            kafkaServer.startup();
        }
    }

    private void shutdownKafkaServers() {
        for (KafkaServer kafkaServer : embeddedKafka().getKafkaServers()) {
            kafkaServer.shutdown();
            kafkaServer.awaitShutdown();
        }
    }

    private Consumer<String, byte[]> consumer() {
        if (consumer == null)
            setupConsumer();
        return consumer;
    }

    private void setupConsumer() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("testGroup", "true", embeddedKafka());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        DefaultKafkaConsumerFactory<String, byte[]> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
        // use unique groupId, so that a new consumer does not get into conflicts with some previous one, which might not yet be fully shutdown
        consumer = cf.createConsumer("testConsumer-" + System.currentTimeMillis(), "someClientIdSuffix");
        embeddedKafka().consumeFromAllEmbeddedTopics(consumer);
    }

}
