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
package one.tomorrow.transactionaloutbox.reactive.service;

import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import one.tomorrow.transactionaloutbox.commons.Longs;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.reactive.tracing.NoopTracingService;
import one.tomorrow.transactionaloutbox.reactive.tracing.TracingService;
import one.tomorrow.transactionaloutbox.reactive.tracing.TracingService.TraceOutboxRecordProcessingResult;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import static java.time.Instant.now;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SOURCE_NAME;

@SuppressWarnings("unused")
public class OutboxProcessor {

    @FunctionalInterface
    public interface KafkaProducerFactory {
        KafkaProducer<String, byte[]> createKafkaProducer();
    }

    /** If provided, the outbox will be cleaned up in the given interval, i.e. outbox records will be
     * deleted if they were processed before `ǹow - retention`. */
    @Value
    @Builder
    public static class CleanupSettings {
        Duration interval;
        Duration retention;
    }

    public static final int DEFAULT_BATCH_SIZE = 100;

    private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);

    private final List<Consumer<KafkaProducer<String, byte[]>>> producerClosedListeners = new ArrayList<>();
    private final List<Consumer<KafkaProducer<String, byte[]>>> producerCreatedListeners = new ArrayList<>();
    private final OutboxLockService lockService;
    private final String lockOwnerId;
    private final OutboxRepository repository;
    private final KafkaProducerFactory producerFactory;
    private final Duration processingInterval;
    private final Duration lockTimeout;
    private final ScheduledExecutorService executor;
    private final ScheduledExecutorService cleanupExecutor;
    private final byte[] eventSource;
    private final int batchSize;
    private final TracingService tracingService;
    private KafkaProducer<String, byte[]> producer;
    @Getter
    private boolean active;
    private Instant lastLockAckquisitionAttempt;

    private ScheduledFuture<?> schedule;
    private ScheduledFuture<?> cleanupSchedule;

    public OutboxProcessor(
            OutboxRepository repository,
            OutboxLockService lockService,
            KafkaProducerFactory producerFactory,
            Duration processingInterval,
            Duration lockTimeout,
            String lockOwnerId,
            String eventSource) {
        this(repository, lockService, producerFactory, processingInterval, lockTimeout, lockOwnerId, eventSource, DEFAULT_BATCH_SIZE, null, null);
    }

    public OutboxProcessor(
            OutboxRepository repository,
            OutboxLockService lockService,
            KafkaProducerFactory producerFactory,
            Duration processingInterval,
            Duration lockTimeout,
            String lockOwnerId,
            String eventSource,
            CleanupSettings cleanupSettings,
            TracingService tracingService) {
        this(repository, lockService, producerFactory, processingInterval, lockTimeout, lockOwnerId, eventSource, DEFAULT_BATCH_SIZE, cleanupSettings, tracingService);
    }

    public OutboxProcessor(
            OutboxRepository repository,
            OutboxLockService lockService,
            KafkaProducerFactory producerFactory,
            Duration processingInterval,
            Duration lockTimeout,
            String lockOwnerId,
            String eventSource,
            int batchSize,
            CleanupSettings cleanupSettings,
            TracingService tracingService) {
        logger.info("Starting outbox processor with lockOwnerId {}, source {} and processing interval {} ms and producer factory {}",
                lockOwnerId, eventSource, processingInterval.toMillis(), producerFactory);
        this.repository = repository;
        this.processingInterval = processingInterval;
        this.lockTimeout = lockTimeout;
        this.lockService = lockService;
        this.lockOwnerId = lockOwnerId;
        this.eventSource = eventSource.getBytes();
        this.batchSize = batchSize;
        this.tracingService = tracingService == null ? new NoopTracingService() : tracingService;
        this.producerFactory = producerFactory;
        createProducer(producerFactory);

        executor = Executors.newSingleThreadScheduledExecutor();

        tryLockAcquisition();

        cleanupExecutor = cleanupSettings != null ? setupCleanupSchedule(repository, cleanupSettings) : null;
    }

    /** Register a callback that's invoked before a producer is closed. */
    public OutboxProcessor onBeforeProducerClosed(Consumer<KafkaProducer<String, byte[]>> listener) {
        producerClosedListeners.add(listener);
        return this;
    }

    /** Register a callback that's invoked when a producer got created. */
    public OutboxProcessor onProducerCreated(Consumer<KafkaProducer<String, byte[]>> listener) {
        producerCreatedListeners.add(listener);

        // also call this for the already created producer (because this might just have happened during construction)
        if (producer != null)
            listener.accept(producer);

        return this;
    }

    private void createProducer(KafkaProducerFactory producerFactory) {
        producer = producerFactory.createKafkaProducer();
        producerCreatedListeners.forEach(listener -> listener.accept(producer));
    }

    private void closeProducer() {
        producerClosedListeners.forEach(listener -> listener.accept(producer));
        producer.close(Duration.ZERO);
    }

    private ScheduledExecutorService setupCleanupSchedule(OutboxRepository repository, CleanupSettings cleanupSettings) {
        final ScheduledExecutorService es = Executors.newSingleThreadScheduledExecutor();
        cleanupSchedule = es.scheduleAtFixedRate(() -> {
            if (active) {
                Instant processedBefore = now().minus(cleanupSettings.getRetention());
                logger.info("Cleaning up outbox records processed before {}", processedBefore);
                repository.deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(processedBefore).block();
            }
        }, 0, cleanupSettings.getInterval().toMillis(), MILLISECONDS);
        return es;
    }

    private void scheduleProcessing() {
        if (executor.isShutdown())
            logger.info("Not scheduling processing for lockOwnerId {} (executor is shutdown)", lockOwnerId);
        else
            schedule = executor.schedule(this::processOutboxWithLock, processingInterval.toMillis(), MILLISECONDS);
    }

    private void scheduleTryLockAcquisition() {
        if (executor.isShutdown())
            logger.info("Not scheduling acquisition of outbox lock for lockOwnerId {} (executor is shutdown)", lockOwnerId);
        else
            schedule = executor.schedule(this::tryLockAcquisition, lockTimeout.toMillis(), MILLISECONDS);
    }

    @PreDestroy
    public void close() {
        logger.info("Stopping OutboxProcessor.");
        if (schedule != null)
            schedule.cancel(false);
        executor.shutdown();

        if (cleanupSchedule != null)
            cleanupSchedule.cancel(false);
        if (cleanupExecutor != null)
            cleanupExecutor.shutdown();

        closeProducer();
        lockService.releaseLock(lockOwnerId).subscribe();
    }

    private void tryLockAcquisition() {
        boolean originalActive = active;
        logger.debug("{} trying to acquire outbox lock", lockOwnerId);

        lockService.acquireOrRefreshLock(lockOwnerId, lockTimeout, active)
                .doOnNext(acquiredOrRefreshedLock -> {
                    this.active = acquiredOrRefreshedLock;
                    lastLockAckquisitionAttempt = now();
                    if (acquiredOrRefreshedLock) {
                        if (originalActive)
                            logger.debug("{} acquired outbox lock, starting to process outbox", lockOwnerId);
                        else
                            logger.info("{} acquired outbox lock, starting to process outbox", lockOwnerId);

                        processOutboxWithLock();
                    }
                    else {
                        scheduleTryLockAcquisition();
                    }
                }).doOnError(e -> {
                    logger.warn("Failed trying to acquire outbox lock, trying again in {}", lockTimeout, e);
                    scheduleTryLockAcquisition();
                }).subscribe();
    }

    private void processOutboxWithLock() {
        if (!active) {
            logger.warn("processOutbox must only be run when in active state");
            scheduleTryLockAcquisition();
            return;
        }

        if (now().isAfter(lastLockAckquisitionAttempt.plus(lockTimeout.dividedBy(2)))) {
            tryLockAcquisition();
            return;
        }

        lockService.runWithLock(lockOwnerId, Mono.defer(() ->
                processOutbox()
                        .onErrorResume(e -> {
                            logger.warn("Recreating producer, due to failure while processing outbox.", e);
                            closeProducer();
                            createProducer(producerFactory);
                            return Mono.empty();
                        })
                        .then()
                ))
                .onErrorResume(e -> {
                    logger.warn("Failed to run with lock, trying to acquire lock in {} ms", lockTimeout.toMillis(), e);
                    active = false;
                    scheduleTryLockAcquisition();
                    return Mono.empty();
                })
                .doOnNext(couldRunWithLock -> {
                    if (couldRunWithLock) {
                        scheduleProcessing();
                    } else {
                        logger.info("Lock was lost, changing to inactive, now trying to acquire lock in {} ms", lockTimeout.toMillis());
                        active = false;
                        scheduleTryLockAcquisition();
                    }
                }).subscribe();
    }

    private Mono<List<OutboxRecord>> processOutbox() {
        return repository.getUnprocessedRecords(batchSize)
                .flatMap(this::publish)
                .concatMap(outboxRecord -> repository.saveInNewTransaction(
                        outboxRecord.toBuilder().processed(now()).build()
                ))
                .collectList();
    }

    private Mono<OutboxRecord> publish(OutboxRecord outboxRecord) {
        return Mono.create(monoSink -> {
            TraceOutboxRecordProcessingResult tracingResult = tracingService.traceOutboxRecordProcessing(outboxRecord);
            ProducerRecord<String, byte[]> producerRecord = toProducerRecord(outboxRecord, tracingResult.getHeaders());
            producer.send(producerRecord, (metadata, exception) -> {
                if (exception != null) {
                    monoSink.error(exception);
                    tracingResult.publishFailed(exception);
                } else {
                    logger.info("Sent record to kafka: {} (got metadata: {})", outboxRecord, metadata);
                    monoSink.success(outboxRecord);
                    tracingResult.publishCompleted();
                }
            });
        });
    }

    private ProducerRecord<String, byte[]> toProducerRecord(OutboxRecord outboxRecord, Map<String, String> headers) {
        ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(
                outboxRecord.getTopic(),
                outboxRecord.getKey(),
                outboxRecord.getValue()
        );
        if (headers != null) {
            headers.forEach((k, v) -> producerRecord.headers().add(k, v.getBytes()));
        }
        producerRecord.headers().add(HEADERS_SEQUENCE_NAME, Longs.toByteArray(outboxRecord.getId()));
        producerRecord.headers().add(HEADERS_SOURCE_NAME, eventSource);
        return producerRecord;
    }

}
