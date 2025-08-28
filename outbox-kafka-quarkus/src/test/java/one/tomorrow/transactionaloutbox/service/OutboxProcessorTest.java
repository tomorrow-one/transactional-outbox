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

import one.tomorrow.transactionaloutbox.config.TestTransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.publisher.MessagePublisher;
import one.tomorrow.transactionaloutbox.publisher.MessagePublisherFactory;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.tracing.NoopTracingService;
import one.tomorrow.transactionaloutbox.tracing.TracingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Thread.sleep;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class OutboxProcessorTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final OutboxLockService lockService = mock(OutboxLockService.class);
    private final MessagePublisherFactory publisherFactory = mock(MessagePublisherFactory.class);
    private final MessagePublisher publisher = mock(MessagePublisher.class);
    private final TracingService tracingService = new NoopTracingService();

    private final String key1 = "r1";
    private final OutboxRecord record1 = mock(OutboxRecord.class, RETURNS_MOCKS);
    private final String key2 = "r2";
    private final OutboxRecord record2 = mock(OutboxRecord.class, RETURNS_MOCKS);
    private final List<OutboxRecord> records = List.of(record1, record2);

    private final Future<?> future1 = mock(Future.class);
    private final Future<?> future2 = mock(Future.class);

    private OutboxProcessor processor;

    @BeforeEach
    void setup() {
        when(publisherFactory.create()).thenReturn(publisher);
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
                publisherFactory,
                lockService,
                tracingService);

        when(record1.getKey()).thenReturn(key1);
        when(record2.getKey()).thenReturn(key2);
    }

    /* Verifies, that all items are submitted to producer.send before the first future.get() is invoked */
    @Test
    void processOutboxShouldUseProducerInternalBatching() throws ExecutionException, InterruptedException {
        when(repository.getUnprocessedRecords(anyInt())).thenReturn(records);

        AtomicInteger sendCounter = new AtomicInteger(0);

        when(publisher.publish(anyLong(), any(), eq(key1), any(), any())).thenAnswer(invocation -> {
            sendCounter.incrementAndGet();
            sleep(10);
            return future1;
        });
        when(publisher.publish(any(), any(), eq(key2), any(), any())).thenAnswer(invocation -> {
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

        when(publisher.publish(any(), any(), eq(key1), any(), any())).thenAnswer(inv -> future1);
        when(future1.get()).thenThrow(new RuntimeException("simulated exception"));

        when(publisher.publish(any(), any(), eq(key2), any(), any())).thenAnswer(inv -> future2);
        when(future2.get()).thenReturn(null);

        processor.processOutbox();

        verify(record1, never()).setProcessed(any());
        verify(repository, never()).update(record1);

        verify(record2).setProcessed(any());
        verify(repository).update(record2);
    }

}
