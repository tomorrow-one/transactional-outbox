package one.tomorrow.transactionaloutbox.service;

import com.google.protobuf.Message;
import kafka.server.KafkaConfig$;
import one.tomorrow.kafka.core.KafkaProtoBufDeserializer;
import one.tomorrow.transactionaloutbox.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.test.Sample.SomethingHappened;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.rule.EmbeddedKafkaRule;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static one.tomorrow.transactionaloutbox.IntegrationTestConfig.DEFAULT_OUTBOX_LOCK_TIMEOUT;
import static one.tomorrow.transactionaloutbox.service.SampleService.Topics.topic1;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;
import static org.springframework.kafka.test.utils.KafkaTestUtils.producerProps;

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
public class OutboxUsageIntegrationTest {

    @ClassRule
    public static EmbeddedKafkaRule kafkaRule = new EmbeddedKafkaRule(1, true, 5, topic1)
            .brokerProperty(KafkaConfig$.MODULE$.HostNameProp(), "127.0.0.1")
            .kafkaPorts(34567);
    private static Consumer<String, Message> consumer;

    @Autowired
    private SampleService sampleService;
    @Autowired
    private ApplicationContext applicationContext;

    private OutboxProcessor outboxProcessor;

    @Before
    public void setup() {
        // now activate the lazy processor
        outboxProcessor = applicationContext.getBean(OutboxProcessor.class);
    }

    @After
    public void tearDown() {
        outboxProcessor.close();
    }

    @AfterClass
    public static void afterClass() {
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
        ConsumerRecords<String, Message> records = KafkaTestUtils.getRecords(consumer(), 5_000);
        assertThat(records.count(), is(1));
        ConsumerRecord<String, Message> kafkaRecord = records.iterator().next();
        assertTrue(kafkaRecord.value() instanceof SomethingHappened);
        SomethingHappened value = (SomethingHappened) kafkaRecord.value();
        assertEquals(id, value.getId());
        assertEquals(name, value.getName());
    }

    private static EmbeddedKafkaBroker embeddedKafka() {
        return kafkaRule.getEmbeddedKafka();
    }

    private static Consumer<String, Message> consumer() {
        if (consumer == null)
            setupConsumer();
        return consumer;
    }

    private static void setupConsumer() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("testGroup", "false", embeddedKafka());
        Map<String, Class<?>> typeMap = new HashMap<>();
        typeMap.put(SomethingHappened.getDescriptor().getFullName(), SomethingHappened.class);
        DefaultKafkaConsumerFactory<String, Message> cf = new DefaultKafkaConsumerFactory<>(
                consumerProps,
                new StringDeserializer(),
                new KafkaProtoBufDeserializer(typeMap)
        );
        consumer = cf.createConsumer();
        embeddedKafka().consumeFromAllEmbeddedTopics(consumer);
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
                    new DefaultKafkaProducerFactory(producerProps(embeddedKafka())),
                    processingInterval,
                    DEFAULT_OUTBOX_LOCK_TIMEOUT,
                    lockOwnerId,
                    eventSource,
                    beanFactory
            );
        }
    }

}
