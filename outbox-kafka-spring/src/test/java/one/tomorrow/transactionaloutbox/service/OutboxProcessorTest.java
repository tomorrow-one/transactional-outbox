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

import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.service.OutboxProcessor.KafkaProducerFactory;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Thread.sleep;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
public class OutboxProcessorTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);

    private final KafkaProducerFactory producerFactory = mock(KafkaProducerFactory.class);

    private final AutowireCapableBeanFactory beanFactory = mock(AutowireCapableBeanFactory.class);

    private final KafkaProducer<String, byte[]> producer = mock(KafkaProducer.class);

    private final OutboxRecord record1 = mock(OutboxRecord.class, RETURNS_MOCKS);
    private final OutboxRecord record2 = mock(OutboxRecord.class, RETURNS_MOCKS);
    private final List<OutboxRecord> records = List.of(record1, record2);

    private final Future<RecordMetadata> future1 = mock(Future.class);
    private final Future<RecordMetadata> future2 = mock(Future.class);

    private OutboxProcessor processor;

    @Before
    public void setup() {
        when(producerFactory.createKafkaProducer()).thenReturn(producer);

        OutboxLockService lockService = mock(OutboxLockService.class);
        when(lockService.getLockTimeout()).thenReturn(Duration.ZERO);
        when(beanFactory.applyBeanPostProcessorsAfterInitialization(any(), anyString())).thenReturn(lockService);

        processor = new OutboxProcessor(
                repository,
                producerFactory,
                Duration.ZERO,
                Duration.ZERO,
                "lockOwnerId",
                "eventSource",
                beanFactory);

        when(record1.getId()).thenReturn(1L);
        when(record1.getKey()).thenReturn("r1");
        when(record2.getId()).thenReturn(2L);
        when(record2.getKey()).thenReturn("r2");
    }

    /* Verifies, that all items are submitted to producer.send before the first future.get() is invoked */
    @Test
    public void processOutboxShouldUseProducerInternalBatching() throws ExecutionException, InterruptedException {
        when(repository.getUnprocessedRecords(anyInt())).thenReturn(records);

        AtomicInteger sendCounter = new AtomicInteger(0);

        when(producer.send(argThat(matching(record1)), any())).thenAnswer(invocation -> {
            sendCounter.incrementAndGet();
            sleep(10);
            return future1;
        });
        when(producer.send(argThat(matching(record2)), any())).thenAnswer(invocation -> {
            sendCounter.incrementAndGet();
            sleep(10);
            return future2;
        });

        when(future1.get()).thenAnswer(invocation -> {
            assertEquals(2, sendCounter.get());
            return null;
        });

        when(future2.get()).thenAnswer(invocation -> {
            assertEquals(2, sendCounter.get());
            return null;
        });

        processor.processOutbox();
    }

    @Test
    public void processOutboxShouldSetProcessedOnlyOnSuccess() {
        when(repository.getUnprocessedRecords(anyInt())).thenReturn(records);

        RecordMetadata metadata = new RecordMetadata(new TopicPartition("t", -1), -1, -1, -1, -1, -1);

        when(producer.send(argThat(matching(record1)), any())).thenAnswer(invocation -> {
            Callback callback = (Callback) invocation.getArguments()[1];
            callback.onCompletion(metadata, new RuntimeException("simulated exception"));
            return future1;
        });
        when(producer.send(argThat(matching(record2)), any())).thenAnswer(invocation -> {
            Callback callback = (Callback) invocation.getArguments()[1];
            callback.onCompletion(metadata, null);
            return future2;
        });

        processor.processOutbox();

        verify(repository, never()).updateProcessed(eq(record1.getId()), any());
        verify(repository).updateProcessed(eq(record2.getId()), any());
    }

    @NotNull
    private static Matcher<ProducerRecord<String, byte[]>> matching(OutboxRecord record) {
        return new BaseMatcher<>() {
            @Override
            public void describeTo(Description description) {
            }

            @Override
            public boolean matches(Object item) {
                if (item == null)
                    return false;
                return Objects.equals(((ProducerRecord<String, byte[]>) item).key(), record.getKey());
            }
        };
    }

}
