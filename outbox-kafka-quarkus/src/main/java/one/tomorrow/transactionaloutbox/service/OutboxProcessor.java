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

import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.tuples.Tuple3;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import one.tomorrow.transactionaloutbox.commons.Longs;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.publisher.KafkaProducerMessagePublisherFactory;
import one.tomorrow.transactionaloutbox.publisher.MessagePublisher;
import one.tomorrow.transactionaloutbox.publisher.MessagePublisherFactory;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.tracing.NoopTracingService;
import one.tomorrow.transactionaloutbox.tracing.TracingService;
import one.tomorrow.transactionaloutbox.tracing.TracingService.TraceOutboxRecordProcessingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

import static java.time.Instant.now;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SOURCE_NAME;

@Startup
@ApplicationScoped
public class OutboxProcessor {

    private static final int BATCH_SIZE = 100;

    private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);

    @Getter
    private final boolean enabled;
    private final OutboxLockService lockService;

    /**
     * Returns the lock owner ID for this processor instance.
     */
    @Getter
    private final String lockOwnerId;
    private final OutboxRepository repository;
    private final MessagePublisherFactory publisherFactory;
    private final Duration processingInterval;
    private final byte[] eventSource;
    private final TracingService tracingService;
    private MessagePublisher publisher;

    /**
     * Returns whether this processor is currently active and processing the outbox.
     * An active processor has acquired the lock and is processing messages.
     */
    @Getter
    private boolean active;

    /**
     * Returns whether this processor has been closed/shut down.
     */
    @Getter
    private boolean closed;

    /**
     * Returns the time of the last lock acquisition attempt, or null if no attempt was made yet.
     */
    @Getter
    private Instant lastLockAcquisitionAttempt;

    private final ScheduledExecutorService scheduledExecutor;
    private final ScheduledExecutorService cleanupExecutor;
    private ScheduledFuture<?> schedule;
    private ScheduledFuture<?> cleanupSchedule;

    /**
     * Constructs an {@code OutboxProcessor} to process the outbox and publish messages to Kafka.
     *
     * @param config             The {@link TransactionalOutboxConfig} containing configuration settings.
     * @param repository         The {@link OutboxRepository} to retrieve and update outbox records.
     *                           Typically, this is instantiated by the framework.
     * @param publisherFactory   A factory to create {@link MessagePublisher} instances for publishing messages.
     *                           By default, the {@link KafkaProducerMessagePublisherFactory} is used.
     * @param lockService        The {@link OutboxLockService} to manage distributed locks for processing.
     *                           Ensures only one instance processes the outbox at a time.
     * @param tracingService     The {@link TracingService} to handle distributed tracing.
     */
    @Inject
    public OutboxProcessor(
            TransactionalOutboxConfig config,
            OutboxRepository repository,
            MessagePublisherFactory publisherFactory,
            OutboxLockService lockService,
            TracingService tracingService) {
        if (config.enabled())
            logger.info("Starting outbox processor with lockOwnerId {}, source {}, processing interval {} ms" +
                        " and publisher factory {}", config.lockOwnerId(), config.eventSource(), config.processingInterval().toMillis(), publisherFactory);
        else
            logger.info("Skipping outbox processor since enabled=false");

        this.enabled = config.enabled();
        this.repository = repository;
        this.lockService = lockService;
        this.processingInterval = config.processingInterval();
        this.lockOwnerId = config.lockOwnerId();
        this.eventSource = config.eventSource().getBytes();
        this.tracingService = tracingService != null ? tracingService : new NoopTracingService();
        this.publisherFactory = publisherFactory;
        publisher = publisherFactory.create();

        if (config.enabled()) {
            scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
            cleanupExecutor = config.cleanup()
                    .map(cleanup -> setupCleanupSchedule(repository, cleanup))
                    .orElse(null);
            tryLockAcquisition(false);
        } else {
            scheduledExecutor = null;
            cleanupExecutor = null;
        }
    }

    private ScheduledExecutorService setupCleanupSchedule(OutboxRepository repository, TransactionalOutboxConfig.CleanupConfig cleanupConfig) {
        final ScheduledExecutorService es = Executors.newSingleThreadScheduledExecutor();
        cleanupSchedule = es.scheduleAtFixedRate(() -> {
            if (active) {
                Instant processedBefore = now().minus(cleanupConfig.retention());
                logger.info("Cleaning up outbox records processed before {}", processedBefore);
                repository.deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(processedBefore);
            }
        }, 0, cleanupConfig.interval().toMillis(), MILLISECONDS);
        return es;
    }

    private void scheduleProcessing() {
        if (scheduledExecutor.isShutdown())
            logger.info("Not scheduling processing for lockOwnerId {} (executor is shutdown)", lockOwnerId);
        else
            schedule = scheduledExecutor.schedule(this::processOutboxWithLock, processingInterval.toMillis(), MILLISECONDS);
    }

    private void scheduleTryLockAcquisition() {
        if (scheduledExecutor.isShutdown())
            logger.info("Not scheduling acquisition of outbox lock for lockOwnerId {} (executor is shutdown)", lockOwnerId);
        else
            schedule = scheduledExecutor.schedule(this::tryLockAcquisitionAndProcess, lockService.getLockTimeout().toMillis(), MILLISECONDS);
    }

    @PreDestroy
    public void close() {
        closed = true;
        if (enabled) {
            logger.info("Stopping OutboxProcessor.");
            if (schedule != null)
                schedule.cancel(false);
            scheduledExecutor.shutdownNow();

            if (cleanupSchedule != null)
                cleanupSchedule.cancel(false);
            if (cleanupExecutor != null)
                cleanupExecutor.shutdownNow();

            publisher.close();
            if (active)
                lockService.releaseLock(lockOwnerId);
        }
    }

    private void tryLockAcquisitionAndProcess() {
        tryLockAcquisition(true);
    }

    private void tryLockAcquisition(boolean processDirectlyIfLocked) {
        try {
            boolean originalActive = active;
            logger.debug("{} trying to acquire outbox lock", lockOwnerId);
            active = lockService.acquireOrRefreshLock(lockOwnerId);
            lastLockAcquisitionAttempt = now();
            if (active) {
                if (originalActive)
                    logger.debug("{} acquired outbox lock, starting to process outbox", lockOwnerId);
                else
                    logger.info("{} acquired outbox lock, starting to process outbox", lockOwnerId);

                if (processDirectlyIfLocked)
                    processOutboxWithLock();
                else
                    scheduleProcessing();
            }
            else
                scheduleTryLockAcquisition();
        } catch (Exception e) {
            if (closed) {
                logger.debug("After closed, failed to acquire outbox lock: {}", e.toString());
            } else {
                logger.warn("Failed trying lock acquisition or processing the outbox, trying again in {}", lockService.getLockTimeout(), e);
                scheduleTryLockAcquisition();
            }
        }
    }

    private void processOutboxWithLock() {
        if (!active)
            throw new IllegalStateException("processOutbox must only be run when in active state");

        if (now().isAfter(lastLockAcquisitionAttempt.plus(lockService.getLockTimeout().dividedBy(2)))) {
            tryLockAcquisitionAndProcess();
            return;
        }

        boolean couldRunWithLock = tryProcessOutbox();
        if (couldRunWithLock) {
            scheduleProcessing();
        } else if (!closed) {
            logger.info("Lock was lost, changing to inactive, now trying to acquire lock in {} ms", lockService.getLockTimeout().toMillis());
            active = false;
            scheduleTryLockAcquisition();
        }

    }

    private boolean tryProcessOutbox() {
        boolean couldRunWithLock = false;
        try {
            couldRunWithLock = lockService.runWithLock(lockOwnerId, () -> {
                try {
                    processOutbox();
                } catch (Throwable e) {
                    if (!closed) {
                        logger.warn("Recreating producer, due to failure while processing outbox.", e);
                        publisher.close();
                        publisher = publisherFactory.create();
                    }
                }
            });
        } catch (Exception e) {
            if (closed)
                logger.debug("After closed, caught exception when trying to run with lock: {}", e.toString());
            else
                logger.warn("Caught exception when trying to run with lock", e);
        }
        return couldRunWithLock;
    }

    void processOutbox() {
        logger.debug("Processing outbox");
        repository.getUnprocessedRecords(BATCH_SIZE)
                .stream()
                .map(outboxRecord -> {
                    TraceOutboxRecordProcessingResult tracingResult =
                            tracingService.traceOutboxRecordProcessing(outboxRecord);
                    Future<?> futureResult = publisher.publish(
                            outboxRecord.getId(),
                            outboxRecord.getTopic(),
                            outboxRecord.getKey(),
                            outboxRecord.getValue(),
                            getHeaders(outboxRecord, tracingResult));
                    return Tuple3.of(outboxRecord, tracingResult, futureResult);
                })
                // collect to List (so that map is completed for all items before awaiting futures),
                // to use producer internal batching
                .toList()
                .forEach(tuple3 -> {
                    OutboxRecord outboxRecord = tuple3.getItem1();
                    TraceOutboxRecordProcessingResult tracingResult = tuple3.getItem2();
                    Future<?> result = tuple3.getItem3();
                    try {
                        await(result);
                        logger.info("Sent record to kafka: {}", outboxRecord);
                        outboxRecord.setProcessed(now());
                        repository.update(outboxRecord);
                        tracingResult.publishCompleted();
                    } catch (RuntimeException e) {
                        logger.warn("Failed to publish {}", outboxRecord, e);
                        tracingResult.publishFailed(e);
                    }
                });
    }

    private Map<String, byte[]> getHeaders(
            OutboxRecord outboxRecord,
            TraceOutboxRecordProcessingResult tracingResult
    ) {
        Map<String, byte[]> headers = new HashMap<>();
        if (tracingResult.getHeaders() != null) {
            tracingResult.getHeaders().forEach((key, value) ->
                    headers.put(key, value.getBytes())
            );
        }
        headers.put(HEADERS_SEQUENCE_NAME, Longs.toByteArray(outboxRecord.getId()));
        headers.put(HEADERS_SOURCE_NAME, eventSource);
        return headers;
    }

    private static void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

}
