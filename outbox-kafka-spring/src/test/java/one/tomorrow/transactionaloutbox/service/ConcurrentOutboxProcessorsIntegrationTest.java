package one.tomorrow.transactionaloutbox.service;

import one.tomorrow.transactionaloutbox.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.LockRepository;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import kafka.server.KafkaConfig$;
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
import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.rule.EmbeddedKafkaRule;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static one.tomorrow.transactionaloutbox.TestUtils.assertConsumedRecord;
import static one.tomorrow.transactionaloutbox.TestUtils.newHeaders;
import static one.tomorrow.transactionaloutbox.TestUtils.newRecord;
import static java.util.stream.IntStream.range;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.springframework.kafka.test.utils.KafkaTestUtils.producerProps;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {
        OutboxRecord.class,
        OutboxRepository.class,
        LockRepository.class,
        TransactionalOutboxRepository.class,
        IntegrationTestConfig.class
})
@TestExecutionListeners({
        DependencyInjectionTestExecutionListener.class,
        FlywayTestExecutionListener.class
})
@FlywayTest
@SuppressWarnings("unused")
public class ConcurrentOutboxProcessorsIntegrationTest {

    private static final String topic = "topicConcurrentTest";
    @ClassRule
    public static EmbeddedKafkaRule kafkaRule = new EmbeddedKafkaRule(1, true, 5, topic)
            .brokerProperty(KafkaConfig$.MODULE$.HostNameProp(), "127.0.0.1")
            .kafkaPorts(34567);
    private static Consumer<String, byte[]> consumer;

    @Autowired
    private SessionFactory sessionFactory;
    @Autowired
    private OutboxRepository repository;
    @Autowired
    private TransactionalOutboxRepository transactionalRepository;
    @Autowired
    private LockRepository lockRepository;
    @Autowired
    private ApplicationContext applicationContext;

    private OutboxProcessor testee1;
    private OutboxProcessor testee2;

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
        LockService lockService = postProcessBeanForTransactionCapabilities(new LockService(lockRepository, lockTimeout));
        Duration processingInterval = Duration.ZERO;
        DefaultKafkaProducerFactory producerFactory = new DefaultKafkaProducerFactory(producerProps(embeddedKafka()));
        testee1 = new OutboxProcessor(repository, producerFactory, processingInterval, lockService, "processor1", "test");
        testee2 = new OutboxProcessor(repository, producerFactory, processingInterval, lockService, "processor2", "test");

        // when
        List<OutboxRecord> outboxRecords = range(0, 1000).mapToObj(
                i -> newRecord(topic, "key1", "value" + i, newHeaders("h", "v" + i))
        ).collect(Collectors.toList());
        outboxRecords.forEach(transactionalRepository::persist);

        // then
        List<ConsumerRecord<String, byte[]>> allRecords = new ArrayList<>();
        while(allRecords.size() < outboxRecords.size()) {
            ConsumerRecords<String, byte[]> records = KafkaTestUtils.getRecords(consumer(), 5_000);
            records.iterator().forEachRemaining(allRecords::add);
        }

        assertThat(allRecords.size(), is(outboxRecords.size()));
        Iterator<ConsumerRecord<String, byte[]>> iter = allRecords.iterator();
        outboxRecords.forEach(outboxRecord -> {
            ConsumerRecord<String, byte[]> kafkaRecord = iter.next();
            assertConsumedRecord(outboxRecord, "h", kafkaRecord);
        });
    }

    private static EmbeddedKafkaBroker embeddedKafka() {
        return kafkaRule.getEmbeddedKafka();
    }

    private static Consumer<String, byte[]> consumer() {
        if (consumer == null)
            setupConsumer();
        return consumer;
    }

    private static void setupConsumer() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("testGroup", "false", embeddedKafka());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        DefaultKafkaConsumerFactory<String, byte[]> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
        // use unique groupId, so that a new consumer does not get into conflicts with some previous one, which might not yet be fully shutdown
        consumer = cf.createConsumer("testConsumer-" + System.currentTimeMillis(), "someClientIdSuffix");
        embeddedKafka().consumeFromAllEmbeddedTopics(consumer);
    }

    @SuppressWarnings("unchecked")
    private <T> T postProcessBeanForTransactionCapabilities(T bean) {
        return (T)applicationContext.getAutowireCapableBeanFactory().applyBeanPostProcessorsAfterInitialization(bean, null);
    }

}
