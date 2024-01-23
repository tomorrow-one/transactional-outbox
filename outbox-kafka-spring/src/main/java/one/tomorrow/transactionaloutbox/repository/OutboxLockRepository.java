/**
 * Copyright 2022-2023 Tomorrow GmbH @ https://tomorrow.one
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

import one.tomorrow.transactionaloutbox.model.OutboxLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static java.time.Instant.now;
import static one.tomorrow.transactionaloutbox.model.OutboxLock.OUTBOX_LOCK_ID;

@Repository
public class OutboxLockRepository {

    private static final Logger logger = LoggerFactory.getLogger(OutboxLockRepository.class);
    private static final ResultSetExtractor<OutboxLock> resultSetExtractor = rs ->
            rs.next()
                    ? new OutboxLock(rs.getString("owner_id"), rs.getTimestamp("valid_until").toInstant())
                    : null;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public OutboxLockRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean acquireOrRefreshLock(String ownerId, Duration timeout) {
        return transactionTemplate.execute(status -> acquireOrRefreshLock(ownerId, timeout,  status));
    }

    private boolean acquireOrRefreshLock(String ownerId, Duration timeout, TransactionStatus txStatus) {

        Instant now = now();
        Instant validUntil;
        try {
            OutboxLock lock = jdbcTemplate.query("select * from outbox_kafka_lock where id = '" + OUTBOX_LOCK_ID + "'", resultSetExtractor);
            if (lock == null) {
                logger.info("No outbox lock found. Creating one for {}", ownerId);
                validUntil = now.plus(timeout);
                jdbcTemplate.update("insert into outbox_kafka_lock (id, owner_id, valid_until) values (?, ?, ?)", OUTBOX_LOCK_ID, ownerId, Timestamp.from(validUntil));
            } else if (ownerId.equals(lock.getOwnerId())) {
                logger.debug("Found outbox lock with requested owner {}, valid until {} - updating lock", lock.getOwnerId(), lock.getValidUntil());
                jdbcTemplate.execute("select * from outbox_kafka_lock where id = '"+ OUTBOX_LOCK_ID + "' for update nowait");
                validUntil = now.plus(timeout);
                jdbcTemplate.update("update outbox_kafka_lock set valid_until = ? where id = '"+ OUTBOX_LOCK_ID + "'", Timestamp.from(validUntil));
            } else if (lock.getValidUntil().isAfter(now)) {
                logger.debug("Found outbox lock with owner {}, valid until {}", lock.getOwnerId(), lock.getValidUntil());
                tryRollback();
                return false;
            } else {
                logger.info("Found expired outbox lock with owner {}, which was valid until {} - grabbing lock for {}", lock.getOwnerId(), lock.getValidUntil(), ownerId);
                jdbcTemplate.execute("select * from outbox_kafka_lock where id = '"+ OUTBOX_LOCK_ID + "' for update nowait");
                validUntil = now.plus(timeout);
                jdbcTemplate.update("update outbox_kafka_lock set owner_id = ?, valid_until = ? where id = '"+ OUTBOX_LOCK_ID + "'",
                        ownerId, Timestamp.from(validUntil));
            }

            txStatus.flush();
            logger.debug("Acquired or refreshed outbox lock for owner {}, valid until {}", ownerId, validUntil);
            return true;
        } catch (UncategorizedSQLException e) {
            return handleException(e, ownerId);
        } catch (DuplicateKeyException e) {
            return handleException(e, ownerId);
        } catch (Throwable e) {
            if (e.getCause() instanceof DuplicateKeyException duplicateKeyException)
                return handleException(duplicateKeyException, ownerId);
            else {
                logger.warn("Outbox lock selection/acquisition for owner {} failed", ownerId, e);
                tryRollback();
                throw e;
            }
        }
    }

    private boolean handleException(UncategorizedSQLException e, String ownerId) {
        if (e.getMessage().contains("could not obtain lock")) {
            String reason = e.getCause() != null ? e.getCause().toString() : e.toString();
            logger.info("Could not grab lock for owner {} - database row is locked: {}", ownerId, reason);
        } else {
            logger.warn("Failed to grab lock for owner {} - uncategorized exception", ownerId, e);
        }
        tryRollback();
        return false;
    }

    private boolean handleException(DuplicateKeyException e, String ownerId) {
        String reason = e.getCause() != null ? e.getCause().toString() : e.toString();
        logger.info("Outbox lock for owner {} could not be created, another one has been created concurrently: {}", ownerId, reason);
        tryRollback();
        return false;
    }

    private void tryRollback() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (Exception ex) {
            logger.info("Caught exception while rolling back OutBox transaction", ex);
        }
    }

    /**
     * Locks the outbox lock row for the given owner if it exists.
     * Must be executed inside some outer transaction.
     *
     * @return true if the lock could be acquired, otherwise false.
     */
    public boolean preventLockStealing(String ownerId) {
        Optional<OutboxLock> lock = queryByOwnerId(ownerId, " for share");
        return lock.isPresent();
    }

    @Transactional
    public void releaseLock(String ownerId) {
        transactionTemplate.executeWithoutResult(status -> queryByOwnerId(ownerId)
                        .ifPresentOrElse(lock -> {
                            jdbcTemplate.update("delete from outbox_kafka_lock where owner_id = ?", ownerId);
                            status.flush();
                            logger.info("Released outbox lock for owner {}", ownerId);
                            },
                            () -> logger.debug("Outbox lock for owner {} not found", ownerId)
                        )
                );
    }

    private Optional<OutboxLock> queryByOwnerId(String ownerId) {
        return queryByOwnerId(ownerId, null);
    }

    private Optional<OutboxLock> queryByOwnerId(String ownerId, String lock) {
        String sql = "select * from outbox_kafka_lock where owner_id = ? ";
        if (StringUtils.hasLength(lock)) {
            sql = sql + lock;
        }
        return Optional.ofNullable(jdbcTemplate.query(sql, resultSetExtractor, ownerId));
    }

}
