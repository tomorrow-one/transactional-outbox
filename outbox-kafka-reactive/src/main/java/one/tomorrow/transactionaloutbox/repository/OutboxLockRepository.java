package one.tomorrow.transactionaloutbox.repository;

import io.r2dbc.spi.Row;
import lombok.AllArgsConstructor;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

import static java.time.Instant.now;
import static one.tomorrow.transactionaloutbox.model.OutboxLock.OUTBOX_LOCK_ID;

@Repository
@AllArgsConstructor
public class OutboxLockRepository {

    private static final Logger logger = LoggerFactory.getLogger(OutboxLockRepository.class);

    private final ReactiveTransactionManager tm;
    private final DatabaseClient db;

    public Mono<Boolean> acquireOrRefreshLock(String ownerId, Duration timeout) {
        TransactionalOperator rxtx = TransactionalOperator.create(tm);
        return selectOutboxLock()
                .flatMap(lock -> handleExistingLock(lock, ownerId, timeout))
                .switchIfEmpty(
                        insertOutboxLock(ownerId, timeout)
                                .onErrorResume(
                                        DataIntegrityViolationException.class,
                                        e -> handleException(e, ownerId)
                                )
                )
                .as(rxtx::transactional);
    }

    private Mono<OutboxLock> selectOutboxLock() {
        return db
                .sql("select * from outbox_kafka_lock where id = :id")
                .bind("id", OUTBOX_LOCK_ID)
                .map(this::toOutboxLock)
                .one();
    }

    private Mono<Boolean> insertOutboxLock(String ownerId, Duration timeout) {
        return Mono.defer(() -> {
            logger.debug("No outbox lock found. Creating one for {}", ownerId);
            return db.sql("insert into outbox_kafka_lock (id, owner_id, valid_until) values (:id, :ownerId, :validUntil)")
                    .bind("id", OUTBOX_LOCK_ID)
                    .bind("ownerId", ownerId)
                    .bind("validUntil", now().plus(timeout))
                    .fetch()
                    .rowsUpdated()
                    .map(rowsUpdated -> rowsUpdated > 0);
        });
    }

    private Mono<Boolean> handleException(DataIntegrityViolationException e, String ownerId) {
        String reason = e.getCause() != null ? e.getCause().toString() : e.toString();
        logger.info("Outbox lock for owner {} could not be created, another one has been created concurrently: {}", ownerId, reason);
        return Mono.just(false);
    }

    private Mono<Boolean> handleExistingLock(OutboxLock lock, String ownerId, Duration timeout) {
        if (ownerId.equals(lock.getOwnerId())) {
            logger.debug("Found outbox lock with requested owner {}, valid until {} - updating lock", lock.getOwnerId(), lock.getValidUntil());
            return db.sql("update outbox_kafka_lock set valid_until = :validUntil where id = :id and owner_id = :ownerId")
                    .bind("validUntil", now().plus(timeout))
                    .bind("id", OUTBOX_LOCK_ID)
                    .bind("ownerId", ownerId)
                    .fetch()
                    .rowsUpdated()
                    .map(rowsUpdated -> rowsUpdated > 0);
        } else if (!ownerId.equals(lock.getOwnerId()) && lock.getValidUntil().isAfter(now())) {
            logger.debug("Found outbox lock with owner {}, valid until {} (now: {})", lock.getOwnerId(), lock.getValidUntil(), now());
            return Mono.just(false);
        } else {
            logger.info("Found expired outbox lock with owner {}, which was valid until {} - grabbing lock for {}", lock.getOwnerId(), lock.getValidUntil(), ownerId);
            return db.sql("update outbox_kafka_lock set owner_id = :ownerId, valid_until = :validUntil where id = :id")
                    .bind("ownerId", ownerId)
                    .bind("validUntil", now().plus(timeout))
                    .bind("id", OUTBOX_LOCK_ID)
                    .fetch()
                    .rowsUpdated()
                    .map(rowsUpdated -> rowsUpdated > 0);
        }
    }

    private OutboxLock toOutboxLock(Row row) {
        return new OutboxLock(row.get("owner_id", String.class), row.get("valid_until", Instant.class));
    }

    /*
    private boolean handleException(LockingStrategyException e, String ownerId, OutboxLock lock) {
        String reason = e.getCause() != null ? e.getCause().toString() : e.toString();
        logger.info("Could not grab lock {} for owner {} - database row is locked: {}", lock, ownerId, reason);
        return false;
    }

    private boolean handleException(ConstraintViolationException e, String ownerId, Transaction tx) {
        String reason = e.getCause() != null ? e.getCause().toString() : e.toString();
        logger.info("Outbox lock for owner {} could not be created, another one has been created concurrently: {}", ownerId, reason);
        if (tx != null) tx.rollback();
        return false;
    }

    public boolean preventLockStealing(String ownerId) {
        Optional<OutboxLock> lock = queryByOwnerId(sessionFactory.getCurrentSession(), ownerId)
                .setLockMode(LockModeType.PESSIMISTIC_READ)
                .uniqueResultOptional();
        return lock.isPresent();
    }
    */

    public Mono<Void> releaseLock(String ownerId) {
        return db.sql("delete from outbox_kafka_lock where owner_id = :ownerId")
                .bind("ownerId", ownerId)
                .fetch()
                .rowsUpdated()
                .doOnNext(rowsUpdated -> {
                    if (rowsUpdated > 0)
                        logger.info("Released outbox lock for owner {}", ownerId);
                    else
                        logger.debug("Outbox lock for owner {} not found, nothing released.", ownerId);
                })
                .then();
    }

}
