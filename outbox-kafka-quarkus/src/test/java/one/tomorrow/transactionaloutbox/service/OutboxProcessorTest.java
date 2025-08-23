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
import one.tomorrow.transactionaloutbox.config.TestTransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.service.OutboxProcessor.KafkaProducerFactory;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Thread.sleep;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class OutboxProcessorTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);

    private final OutboxLockService lockService = mock(OutboxLockService.class);

    private final KafkaProducerFactory producerFactory = mock(KafkaProducerFactory.class);

    private final KafkaProducer<String, byte[]> producer = mock(KafkaProducer.class);

    private final OutboxRecord record1 = mock(OutboxRecord.class, RETURNS_MOCKS);
    private final OutboxRecord record2 = mock(OutboxRecord.class, RETURNS_MOCKS);
    private final List<OutboxRecord> records = List.of(record1, record2);

    private final Future<RecordMetadata> future1 = mock(Future.class);
    private final Future<RecordMetadata> future2 = mock(Future.class);

    private OutboxProcessor processor;

    @BeforeEach
    void setup() {
        when(producerFactory.createKafkaProducer()).thenReturn(producer);

        when(lockService.getLockTimeout()).thenReturn(Duration.ZERO);

        TransactionalOutboxConfig config = TestTransactionalOutboxConfig.createConfig(
                Duration.ZERO,
                Duration.ZERO,
                "lockOwnerId",
                "eventSource"
        );

        processor = new OutboxProcessor(
                config,
                repository,
                producerFactory,
                lockService);

        when(record1.getKey()).thenReturn("r1");
        when(record2.getKey()).thenReturn("r2");
    }

    /* Verifies, that all items are submitted to producer.send before the first future.get() is invoked */
    @Test
    void processOutboxShouldUseProducerInternalBatching() throws ExecutionException, InterruptedException {
        when(repository.getUnprocessedRecords(anyInt())).thenReturn(records);

        AtomicInteger sendCounter = new AtomicInteger(0);

        when(producer.send(argThat(matching(record1)))).thenAnswer(invocation -> {
            sendCounter.incrementAndGet();
            sleep(10);
            return future1;
        });
        when(producer.send(argThat(matching(record2)))).thenAnswer(invocation -> {
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
    void processOutboxShouldSetProcessedOnlyOnSuccess() throws Exception {
        when(repository.getUnprocessedRecords(anyInt())).thenReturn(records);

        RecordMetadata metadata = new RecordMetadata(new TopicPartition("t", -1), -1, -1, -1, -1, -1);

        when(producer.send(argThat(matching(record1)))).thenReturn(future1);
        when(future1.get()).thenThrow(new RuntimeException("simulated exception"));

        when(producer.send(argThat(matching(record2)))).thenReturn(future2);
        when(future2.get()).thenReturn(metadata);

        processor.processOutbox();

        verify(record1, never()).setProcessed(any());
        verify(repository, never()).update(record1);

        verify(record2).setProcessed(any());
        verify(repository).update(record2);
    }

    @NotNull
    private static ArgumentMatcher<ProducerRecord<String, byte[]>> matching(OutboxRecord outboxRecord) {
        return new ArgumentMatcher<>() {
            @Override
            public Class<?> type() {
                return ProducerRecord.class;
            }

            @Override
            public boolean matches(ProducerRecord<String, byte[]> item) {
                if (item == null)
                    return false;
                return Objects.equals(item.key(), outboxRecord.getKey());
            }
        };
    }

}
