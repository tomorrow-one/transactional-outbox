package one.tomorrow.transactionaloutbox.reactive.service;

import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static java.time.Instant.now;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static one.tomorrow.kafka.core.KafkaConstants.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.kafka.core.KafkaConstants.HEADERS_SOURCE_NAME;

public class OutboxProcessor {

	@FunctionalInterface
	public interface KafkaProducerFactory {
		KafkaProducer<String, byte[]> createKafkaProducer();
	}

	private static final int DEFAULT_BATCH_SIZE = 100;

	private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);

	private final OutboxLockService lockService;
	private final String lockOwnerId;
	private final OutboxRepository repository;
	private final KafkaProducerFactory producerFactory;
	private final Duration processingInterval;
	private final Duration lockTimeout;
	private final ScheduledExecutorService executor;
	private final byte[] eventSource;
	private final int batchSize;
	private KafkaProducer<String, byte[]> producer;
	private boolean active;
	private Instant lastLockAckquisitionAttempt;

	private ScheduledFuture<?> schedule;

	public OutboxProcessor(
			OutboxRepository repository,
			OutboxLockService lockService,
			KafkaProducerFactory producerFactory,
			Duration processingInterval,
			Duration lockTimeout,
			String lockOwnerId,
			String eventSource) {
		this(repository, lockService, producerFactory, processingInterval, lockTimeout, lockOwnerId, eventSource, DEFAULT_BATCH_SIZE);
	}

	public OutboxProcessor(
			OutboxRepository repository,
			OutboxLockService lockService,
			KafkaProducerFactory producerFactory,
			Duration processingInterval,
			Duration lockTimeout,
			String lockOwnerId,
			String eventSource,
			int batchSize) {
		logger.info("Starting outbox processor with lockOwnerId {}, source {} and processing interval {} ms and producer factory {}",
				lockOwnerId, eventSource, processingInterval.toMillis(), producerFactory);
		this.repository = repository;
		this.processingInterval = processingInterval;
		this.lockTimeout = lockTimeout;
		this.lockService = lockService;
		this.lockOwnerId = lockOwnerId;
		this.eventSource = eventSource.getBytes();
		this.batchSize = batchSize;
		this.producerFactory = producerFactory;
		producer = producerFactory.createKafkaProducer();

		executor = Executors.newSingleThreadScheduledExecutor();

		tryLockAcquisition();
	}

	private void scheduleProcessing() {
		schedule = executor.schedule(this::processOutboxWithLock, processingInterval.toMillis(), MILLISECONDS);
	}

	private void scheduleTryLockAcquisition() {
		schedule = executor.schedule(this::tryLockAcquisition, lockTimeout.toMillis(), MILLISECONDS);
	}

	@PreDestroy
	public void close() {
		logger.info("Stopping OutboxProcessor.");
		if (schedule != null)
			schedule.cancel(false);
		executor.shutdown();
		producer.close(Duration.ZERO);
		lockService.releaseLock(lockOwnerId).subscribe();
	}

	private void tryLockAcquisition() {
		boolean originalActive = active;
		logger.debug("{} trying to acquire outbox lock", lockOwnerId);

		lockService.acquireOrRefreshLock(lockOwnerId, lockTimeout)
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
							producer.close(Duration.ZERO);
							producer = producerFactory.createKafkaProducer();
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
			ProducerRecord<String, byte[]> producerRecord = toProducerRecord(outboxRecord);
			producer.send(producerRecord, (metadata, exception) -> {
				if (exception != null) {
					monoSink.error(exception);
				} else {
					logger.info("Sent record to kafka: {} (got metadata: {})", outboxRecord, metadata);
					monoSink.success(outboxRecord);
				}
			});
		});
	}

	private ProducerRecord<String, byte[]> toProducerRecord(OutboxRecord outboxRecord) {
		ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(
				outboxRecord.getTopic(),
				outboxRecord.getKey(),
				outboxRecord.getValue()
		);
		Map<String, String> headers = outboxRecord.getHeadersAsMap();
		if (headers != null) {
			headers.forEach((k, v) -> producerRecord.headers().add(k, v.getBytes()));
		}
		producerRecord.headers().add(HEADERS_SEQUENCE_NAME, Numbers.toByteArray(outboxRecord.getId()));
		producerRecord.headers().add(HEADERS_SOURCE_NAME, eventSource);
		return producerRecord;
	}


}
