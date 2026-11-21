/**
 * Copyright 2026 Tomorrow GmbH @ https://tomorrow.one
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
package one.tomorrow.transactionaloutbox.reactive.service;

import one.tomorrow.transactionaloutbox.metrics.OutboxMetrics;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxRepository;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxProcessorMetricsTest {
    @ParameterizedTest
    @ValueSource(strings = {"success", "callbackFailure", "syncFailure", "dbFailure", "brokenMetrics"})
    @SuppressWarnings("unchecked")
    void observesPublishingWithoutChangingDelivery(String scenario) {
        var repository = mock(OutboxRepository.class);
        var lock = mock(OutboxLockService.class);
        KafkaProducer<String, byte[]> producer = mock(KafkaProducer.class);
        var record = OutboxRecord.builder().id(1L).topic("topic").created(Instant.now()).value(new byte[]{1}).build();
        when(repository.getUnprocessedRecords(anyInt())).thenReturn(Flux.just(record));
        when(repository.saveInNewTransaction(any())).thenAnswer(inv -> scenario.equals("dbFailure")
                ? Mono.error(new IllegalStateException("database unavailable")) : Mono.just(inv.getArgument(0)));
        when(lock.acquireOrRefreshLock(anyString(), any(), anyBoolean())).thenReturn(Mono.just(true));
        when(lock.releaseLock(anyString())).thenReturn(Mono.empty());
        when(lock.runWithLock(anyString(), any())).thenAnswer(inv -> ((Mono<?>) inv.getArgument(1)).thenReturn(true));
        when(producer.send(any(), any())).thenAnswer(inv -> {
            if (scenario.equals("syncFailure")) throw new IllegalArgumentException("invalid record");
            Callback callback = inv.getArgument(1);
            callback.onCompletion(null, scenario.equals("callbackFailure") ? new IllegalStateException("broker unavailable") : null);
            return CompletableFuture.completedFuture(null);
        });
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        OutboxMetrics metrics = new OutboxMetrics() {
            public Attempt startPublish() {
                return success -> {
                    if (scenario.equals("brokenMetrics")) throw new IllegalStateException("metrics unavailable");
                    (success ? succeeded : failed).incrementAndGet();
                };
            }
        };
        var processor = new OutboxProcessor(repository, lock, () -> producer,
                Duration.ofHours(1), Duration.ofHours(2), "owner", "source", 100, null, null, metrics);
        try {
            boolean published = !scenario.equals("callbackFailure") && !scenario.equals("syncFailure");
            if (!scenario.equals("brokenMetrics")) {
                assertEquals(published ? 1 : 0, succeeded.get());
                assertEquals(published ? 0 : 1, failed.get());
            }
            verify(repository, times(published ? 1 : 0)).saveInNewTransaction(any());
            verify(repository, never()).getPendingMetrics();
        } finally { processor.close(); }
    }
}
