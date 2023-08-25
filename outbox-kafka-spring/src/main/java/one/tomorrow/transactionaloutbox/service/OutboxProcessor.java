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

import one.tomorrow.transactionaloutbox.commons.Longs;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static java.time.Instant.now;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SOURCE_NAME;

public class OutboxProcessor {

	@FunctionalInterface
	public interface KafkaProducerFactory {
		KafkaProducer<String, byte[]> createKafkaProducer();
	}

	private static final int BATCH_SIZE = 100;

	private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);

	private final OutboxLockService lockService;
	private final String lockOwnerId;
	private final OutboxRepository repository;
	private final KafkaProducerFactory producerFactory;
	private final Duration processingInterval;
	private final ScheduledExecutorService executor;
	private final byte[] eventSource;
	private KafkaProducer<String, byte[]> producer;
	private boolean active;
	private Instant lastLockAckquisitionAttempt;

	private ScheduledFuture<?> schedule;

	public OutboxProcessor(
			OutboxRepository repository,
			KafkaProducerFactory producerFactory,
			Duration processingInterval,
			Duration lockTimeout,
			String lockOwnerId,
			String eventSource,
			AutowireCapableBeanFactory beanFactory) {
		logger.info("Starting outbox processor with lockOwnerId {}, source {} and processing interval {} ms and producer factory {}",
				lockOwnerId, eventSource, processingInterval.toMillis(), producerFactory);
		this.repository = repository;
		this.processingInterval = processingInterval;
		OutboxLockRepository lockRepository = beanFactory.getBean(OutboxLockRepository.class);
		OutboxLockService rawLockService = new OutboxLockService(lockRepository, lockTimeout);
		this.lockService = (OutboxLockService) beanFactory.applyBeanPostProcessorsAfterInitialization(rawLockService, "OutboxLockService");
		this.lockOwnerId = lockOwnerId;
		this.eventSource = eventSource.getBytes();
		this.producerFactory = producerFactory;
		producer = producerFactory.createKafkaProducer();

		executor = Executors.newSingleThreadScheduledExecutor();

		tryLockAcquisition();
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
			schedule = executor.schedule(this::tryLockAcquisition, lockService.getLockTimeout().toMillis(), MILLISECONDS);
	}

	@PreDestroy
	public void close() {
		logger.info("Stopping OutboxProcessor.");
		if (schedule != null)
			schedule.cancel(false);
		executor.shutdown();
		producer.close();
		if (active)
			lockService.releaseLock(lockOwnerId);
	}

	private void tryLockAcquisition() {
		try {
			boolean originalActive = active;
			logger.debug("{} trying to acquire outbox lock", lockOwnerId);
			active = lockService.acquireOrRefreshLock(lockOwnerId);
			lastLockAckquisitionAttempt = now();
			if (active) {
				if (originalActive)
					logger.debug("{} acquired outbox lock, starting to process outbox", lockOwnerId);
				else
					logger.info("{} acquired outbox lock, starting to process outbox", lockOwnerId);

				processOutboxWithLock();
			}
			else
				scheduleTryLockAcquisition();
		} catch (Exception e) {
			logger.warn("Failed trying lock acquisition or processing the outbox, trying again in {}", lockService.getLockTimeout(), e);
			scheduleTryLockAcquisition();
		}
	}

	private void processOutboxWithLock() {
		if (!active)
			throw new IllegalStateException("processOutbox must only be run when in active state");

		if (now().isAfter(lastLockAckquisitionAttempt.plus(lockService.getLockTimeout().dividedBy(2)))) {
			tryLockAcquisition();
			return;
		}

        boolean couldRunWithLock = tryProcessOutbox();
        if (couldRunWithLock) {
            scheduleProcessing();
        } else {
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
                    logger.warn("Recreating producer, due to failure while processing outbox.", e);
                    producer.close();
                    producer = producerFactory.createKafkaProducer();
                }
            });
        } catch (Exception e) {
            logger.warn("Caught exception when trying to run with lock.", e);
        }
        return couldRunWithLock;
    }

	private void processOutbox() throws ExecutionException, InterruptedException {
		List<OutboxRecord> records = repository.getUnprocessedRecords(BATCH_SIZE);
		for (OutboxRecord outboxRecord : records) {
			ProducerRecord<String, byte[]> producerRecord = toProducerRecord(outboxRecord);
			Future<RecordMetadata> result = producer.send(producerRecord);
			result.get();
			logger.info("Sent record to kafka: {}", outboxRecord);
			outboxRecord.setProcessed(now());
			repository.update(outboxRecord);
		}
	}

	private ProducerRecord<String, byte[]> toProducerRecord(OutboxRecord outboxRecord) {
		ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(
				outboxRecord.getTopic(),
				outboxRecord.getKey(),
				outboxRecord.getValue()
		);
		if (outboxRecord.getHeaders() != null) {
			outboxRecord.getHeaders().forEach((k, v) -> producerRecord.headers().add(k, v.getBytes()));
		}
		producerRecord.headers().add(HEADERS_SEQUENCE_NAME, Longs.toByteArray(outboxRecord.getId()));
		producerRecord.headers().add(HEADERS_SOURCE_NAME, eventSource);
		return producerRecord;
	}


}
