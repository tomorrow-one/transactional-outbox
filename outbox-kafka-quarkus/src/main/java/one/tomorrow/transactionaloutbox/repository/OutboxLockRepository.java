/**
 * Copyright 2025 Tomorrow GmbH @ https://tomorrow.one
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

import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static java.time.Instant.now;

@Singleton
@AllArgsConstructor
public class OutboxLockRepository {

    private static final Logger logger = LoggerFactory.getLogger(OutboxLockRepository.class);

    private static final Map<String, Object> PESSIMISTIC_LOCK_PROPS =
            Map.of("jakarta.persistence.lock.timeout", 0);

    private final EntityManager entityManager;

    /**
     * Acquires or refreshes a lock for the given owner ID with the specified timeout.
     *
     * @param ownerId the ID of the owner trying to acquire the lock
     * @param timeout duration until the lock expires
     * @return true if the lock was acquired or refreshed, false otherwise
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean acquireOrRefreshLock(String ownerId, Duration timeout) {
        OutboxLock lock = null;
        Instant now = now();
        try {
            lock = entityManager.find(OutboxLock.class, OutboxLock.OUTBOX_LOCK_ID);
            if (lock == null) {
                logger.debug("No outbox lock found. Creating one for {}", ownerId);
                lock = new OutboxLock(ownerId, now.plus(timeout));
            } else {
                if (ownerId.equals(lock.getOwnerId())) {
                    logger.debug("Found outbox lock with requested owner {}, valid until {} - updating lock", lock.getOwnerId(), lock.getValidUntil());
                    entityManager.lock(lock, LockModeType.PESSIMISTIC_WRITE, PESSIMISTIC_LOCK_PROPS);
                    lock.setValidUntil(now.plus(timeout));
                } else if (lock.getValidUntil().isAfter(now)) {
                    logger.debug("Found outbox lock with owner {}, valid until {}", lock.getOwnerId(), lock.getValidUntil());
                    setRollbackOnly();
                    return false;
                } else {
                    logger.info("Found expired outbox lock with owner {}, which was valid until {} - grabbing lock for {}", lock.getOwnerId(), lock.getValidUntil(), ownerId);
                    entityManager.lock(lock, LockModeType.PESSIMISTIC_WRITE, PESSIMISTIC_LOCK_PROPS);
                    lock.setOwnerId(ownerId);
                    lock.setValidUntil(now.plus(timeout));
                }
            }

            entityManager.persist(lock);
            entityManager.flush();
            logger.info("Acquired or refreshed outbox lock for owner {}, valid until {}", ownerId, lock.getValidUntil());
            return true;
        } catch (PersistenceException e) {
            if (e.getCause() instanceof ConstraintViolationException) {
                return handleConstraintViolation(e, ownerId);
            } else {
                return handleLockingException(e, ownerId, lock);
            }
        } catch (Throwable e) {
            logger.warn("Outbox lock selection/acquisition for owner {} failed", ownerId, e);
            setRollbackOnly();
            throw e;
        }
    }

    private boolean handleLockingException(Exception e, String ownerId, OutboxLock lock) {
        String reason = e.getCause() != null ? e.getCause().toString() : e.toString();
        logger.info("Could not grab lock {} for owner {} - database row is locked: {}", lock, ownerId, reason);
        setRollbackOnly();
        return false;
    }

    private boolean handleConstraintViolation(Exception e, String ownerId) {
        String reason = e.getCause() != null ? e.getCause().toString() : e.toString();
        logger.info("Outbox lock for owner {} could not be created, another one has been created concurrently: {}", ownerId, reason);
        setRollbackOnly();
        return false;
    }

    private void setRollbackOnly() {
        try {
            jakarta.transaction.TransactionManager transactionManager = com.arjuna.ats.jta.TransactionManager.transactionManager();
            if (transactionManager.getTransaction() != null) {
                transactionManager.getTransaction().setRollbackOnly();
            }
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
        Optional<OutboxLock> lock = queryByOwnerId(ownerId)
                .setLockMode(LockModeType.PESSIMISTIC_READ)
                .getResultStream()
                .findFirst();
        return lock.isPresent();
    }

    /**
     * Releases the lock held by the specified owner
     *
     * @param ownerId the ID of the owner whose lock should be released
     */
    @Transactional
    public void releaseLock(String ownerId) {
        queryByOwnerId(ownerId)
                .getResultStream()
                .findFirst()
                .ifPresentOrElse(lock -> {
                            entityManager.remove(lock);
                            entityManager.flush();
                            logger.info("Released outbox lock for owner {}", ownerId);
                        },
                        () -> logger.debug("Outbox lock for owner {} not found", ownerId)
                );
    }

    private TypedQuery<OutboxLock> queryByOwnerId(String ownerId) {
        return entityManager
                .createQuery("FROM OutboxLock WHERE ownerId = :ownerId", OutboxLock.class)
                .setParameter("ownerId", ownerId);
    }
}
