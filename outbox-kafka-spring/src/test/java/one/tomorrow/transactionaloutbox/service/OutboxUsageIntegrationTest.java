/**
 * Copyright 2023 Tomorrow GmbH @ https://tomorrow.one
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package one.tomorrow.transactionaloutbox.service;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTraceContext;
import io.micrometer.tracing.test.simple.SimpleTracer;
import one.tomorrow.transactionaloutbox.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.KafkaTestSupport;
import one.tomorrow.transactionaloutbox.TestUtils;
import one.tomorrow.transactionaloutbox.commons.ProxiedKafkaContainer;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.tracing.MicrometerTracingIntegrationTestConfig;
import one.tomorrow.transactionaloutbox.tracing.TracingAssertions;
import one.tomorrow.transactionaloutbox.tracing.TracingService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.stream.StreamSupport.stream;
import static one.tomorrow.transactionaloutbox.IntegrationTestConfig.DEFAULT_OUTBOX_LOCK_TIMEOUT;
import static one.tomorrow.transactionaloutbox.commons.CommonKafkaTestSupport.createConsumer;
import static one.tomorrow.transactionaloutbox.commons.CommonKafkaTestSupport.producerProps;
import static one.tomorrow.transactionaloutbox.commons.ProxiedKafkaContainer.bootstrapServers;
import static one.tomorrow.transactionaloutbox.service.SampleService.Topics.topic1;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        OutboxRecord.class,
        OutboxRepository.class,
        OutboxLock.class,
        OutboxLockRepository.class,
        OutboxService.class,
        SampleService.class,
        IntegrationTestConfig.class,
        OutboxUsageIntegrationTest.OutboxProcessorSetup.class,
        MicrometerTracingIntegrationTestConfig.class
})
@TestExecutionListeners({
        DependencyInjectionTestExecutionListener.class,
        FlywayTestExecutionListener.class
})
@FlywayTest
@SuppressWarnings("unused")
public class OutboxUsageIntegrationTest implements KafkaTestSupport<String>, TracingAssertions {

    public static final ProxiedKafkaContainer kafkaContainer = ProxiedKafkaContainer.startProxiedKafka();
    private static Consumer<String, String> consumer;

    @Autowired
    private SampleService sampleService;
    @Autowired
    private SimpleTracer tracer;
    @Autowired
    private Propagator tracingPropagator;
    @Autowired
    private ApplicationContext applicationContext;

    private static OutboxProcessor outboxProcessor;

    @BeforeEach
    public void setup() {
        // now activate the lazy processor
        if (outboxProcessor == null)
            outboxProcessor = applicationContext.getBean(OutboxProcessor.class);
    }

    @AfterAll
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

    @Test
    public void should_SaveEventForPublishing_withTracingHeaders() {
        // given
        Span span = tracer.nextSpan().name("test-span").start();
        String traceId = span.context().traceId();

        int id = 25;
        String name = "tracing test";
        SampleService.Header[] additionalHeader = TestUtils.randomBoolean()
                ? new SampleService.Header[]{new SampleService.Header("key", "value")}
                : new SampleService.Header[0];

        OutboxRecord outboxRecord;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            // when
            outboxRecord = sampleService.doSomethingWithAdditionalHeaders(id, name, additionalHeader);
        } finally {
            span.end();
        }

        // then
        ConsumerRecords<String, String> records = getAndCommitRecords();
        assertThat(records.count(), is(1));
        ConsumerRecord<String, String> kafkaRecord = records.iterator().next();

        assertEquals(id, Integer.parseInt(kafkaRecord.key()));
        assertEquals(name, kafkaRecord.value());

        // 3 spans:
        // * one created in the test
        // * one for the transactional-outbox
        // * one for the processing to Kafka
        assertEquals(3, tracer.getSpans().size());

        // check first span, just to be sure
        Iterator<SimpleSpan> spanIterator = tracer.getSpans().iterator();
        SimpleSpan testSpan = spanIterator.next();
        assertEquals(traceId, testSpan.getTraceId());
        assertEquals(span.context().spanId(), testSpan.getSpanId());

        // verify recorded span for the outbox record in the transactional-outbox
        SimpleSpan outboxSpan = spanIterator.next();
        assertOutboxSpan(outboxSpan, traceId, span.context().spanId(), outboxRecord);

        // verify recorded span for the processing to Kafka
        SimpleSpan processingSpan = spanIterator.next();
        SimpleTraceContext processingSpanContext = processingSpan.context();
        assertProcessingSpan(processingSpanContext, traceId, outboxSpan.context().spanId());

        // verify consumer record headers
        Map<String, String> headers = stream(kafkaRecord.headers().spliterator(), false)
                .collect(Collectors.toMap(Header::key, h -> new String(h.value())));
        // the span on consumer side might also be built from scratch, with a "follows_from" relationship to the parent
        TraceContext consumerSpan = tracingPropagator.extract(headers, Map::get)
                .kind(Span.Kind.CONSUMER)
                .start()
                .context();
        assertEquals(traceId, consumerSpan.traceId());
        assertEquals(processingSpanContext.spanId(), consumerSpan.parentId());

        for (SampleService.Header header : additionalHeader) {
            assertThat(headers, hasEntry(header.getKey(), header.getValue()));
        }
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
        @Bean
        @Lazy
        // if not lazy, this is loaded before the FlywayTestExecutionListener got activated and created the needed tables
        public OutboxProcessor outboxProcessor(
                OutboxRepository repository,
                TracingService tracingService,
                AutowireCapableBeanFactory beanFactory
        ) {
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
                    null,
                    tracingService,
                    beanFactory
            );
        }
    }

}
