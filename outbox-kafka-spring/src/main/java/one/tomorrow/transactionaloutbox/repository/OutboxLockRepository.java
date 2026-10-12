/**
 * Copyright 2022-2026 Tomorrow GmbH @ https://tomorrow.one
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
package one.tomorrow.transactionaloutbox.repository;

import one.tomorrow.transactionaloutbox.commons.DatabaseDialect;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static one.tomorrow.transactionaloutbox.model.OutboxLock.OUTBOX_LOCK_ID;

@Repository
public class OutboxLockRepository {

    private static final Logger logger = LoggerFactory.getLogger(OutboxLockRepository.class);
    private static final ResultSetExtractor<OutboxLock> ROW_EXTRACTOR = rs -> rs.next()
            ? new OutboxLock(rs.getString("owner_id"), rs.getTimestamp("valid_until").toInstant()) : null;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public OutboxLockRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean acquireOrRefreshLock(String ownerId, Duration timeout) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                OutboxLock lock = selectLock(false);
                if (lock != null) {
                    if (isHeldByAnotherOwner(lock, ownerId))
                        return false;
                    // A locking read sees the latest committed row, including under MySQL REPEATABLE READ.
                    // Recheck ownership after acquiring the lock, not against the earlier snapshot.
                    lock = selectLock(true);
                    if (lock != null && isHeldByAnotherOwner(lock, ownerId))
                        return false;
                }
                Timestamp validUntil = Timestamp.from(Instant.now().plus(timeout));
                if (lock == null) {
                    jdbcTemplate.update("insert into outbox_kafka_lock (id, owner_id, valid_until) values (?, ?, ?)",
                            OUTBOX_LOCK_ID, ownerId, validUntil);
                } else {
                    jdbcTemplate.update("update outbox_kafka_lock set owner_id = ?, valid_until = ? where id = ?",
                            ownerId, validUntil, OUTBOX_LOCK_ID);
                }
                return true;
            }));
        } catch (RuntimeException e) {
            // Handle contention only after the transaction has rolled back.
            if (isContention(e)) {
                logger.debug("Outbox lock is unavailable for owner {}", ownerId, e);
                return false;
            }
            throw e;
        }
    }

    private OutboxLock selectLock(boolean forUpdate) {
        return jdbcTemplate.query("select * from outbox_kafka_lock where id = ?"
                + (forUpdate ? " for update nowait" : ""), ROW_EXTRACTOR, OUTBOX_LOCK_ID);
    }

    private boolean isHeldByAnotherOwner(OutboxLock lock, String ownerId) {
        return !ownerId.equals(lock.getOwnerId()) && lock.getValidUntil().isAfter(Instant.now());
    }

    private boolean isContention(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException || cause instanceof PessimisticLockingFailureException)
                return true;
            if (cause instanceof SQLException sql
                    && DatabaseDialect.isLockContention(sql.getSQLState(), sql.getErrorCode()))
                return true;
        }
        return false;
    }

    /** Must run inside the caller's transaction, which holds the shared lock until commit. */
    public boolean preventLockStealing(String ownerId) {
        return jdbcTemplate.query("select * from outbox_kafka_lock where id = ? and owner_id = ? for share",
                ROW_EXTRACTOR, OUTBOX_LOCK_ID, ownerId) != null;
    }

    @Transactional
    public void releaseLock(String ownerId) {
        jdbcTemplate.update("delete from outbox_kafka_lock where id = ? and owner_id = ?", OUTBOX_LOCK_ID, ownerId);
    }
}
