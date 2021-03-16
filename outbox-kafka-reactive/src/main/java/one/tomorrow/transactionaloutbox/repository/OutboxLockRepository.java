package one.tomorrow.transactionaloutbox.repository;

import io.r2dbc.spi.Row;
import lombok.AllArgsConstructor;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
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

    private final DatabaseClient db;
    private final TransactionalOperator rxtx;

    public Mono<Boolean> acquireOrRefreshLock(String ownerId, Duration timeout) {
        return selectOutboxLock()
                .flatMap(lock -> handleExistingLock(lock, ownerId, timeout))
                .switchIfEmpty(
                        insertOutboxLock(ownerId, timeout)
                )
                .as(rxtx::transactional)
                .onErrorResume(
                        DataIntegrityViolationException.class,
                        e -> handleDuplicateKey(e, ownerId)
                )
                .onErrorResume(
                        e -> e instanceof DataAccessResourceFailureException && e.toString().contains("could not obtain lock"),
                        e -> handleRowIsLocked(e, ownerId)
                );
    }

    private Mono<Boolean> handleDuplicateKey(DataIntegrityViolationException e, String ownerId) {
        logger.info("Outbox lock for owner {} could not be created, another one has been created concurrently: {}", ownerId, e);
        return Mono.just(false);
    }

    private Mono<Boolean> handleRowIsLocked(Throwable e, String ownerId) {
        logger.info("Could not grab lock for owner {} - database row is locked: {}", ownerId, e);
        return Mono.just(false);
    }

    private Mono<OutboxLock> selectOutboxLock() {
        return db.sql("select * from outbox_kafka_lock where id = :id FOR SHARE NOWAIT") //  FOR KEY SHARE NOWAIT
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
        logger.info("Found lock for {}", row.get("owner_id", String.class));
        return new OutboxLock(row.get("owner_id", String.class), row.get("valid_until", Instant.class));
    }

    public Mono<Boolean> preventLockStealing(String ownerId) {
        return lockOutboxLock(ownerId)
                //db.sql("LOCK outbox_kafka_lock IN ACCESS SHARE MODE NOWAIT").then()
                .thenReturn(true)
                .defaultIfEmpty(false);
    }

    private Mono<OutboxLock> lockOutboxLock(String ownerId) {
        return db.sql("select * from outbox_kafka_lock where owner_id = :ownerId for update")
                        .bind("ownerId", ownerId)
                        .map(this::toOutboxLock)
                        .one();
    }

    public Mono<Void> releaseLock(String ownerId) {
        return db.sql("delete from outbox_kafka_lock where owner_id = :ownerId")
                .bind("ownerId", ownerId)
                .fetch()
                .rowsUpdated()
                .doOnNext(rowsUpdated -> {
                    if (rowsUpdated > 0)
                        logger.info("Released outbox lock for owner {}", ownerId);
                    else
                        logger.info("Outbox lock for owner {} not found, nothing released.", ownerId);
                })
                .then();
    }

}
