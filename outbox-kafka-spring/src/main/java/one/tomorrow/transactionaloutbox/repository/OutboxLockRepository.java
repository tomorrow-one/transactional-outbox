package one.tomorrow.transactionaloutbox.repository;

import lombok.AllArgsConstructor;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.dialect.lock.LockingStrategyException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.LockModeType;
import java.time.Duration;
import java.util.Optional;

import static java.time.Instant.now;

@Repository
@AllArgsConstructor
public class OutboxLockRepository {

    private static final Logger logger = LoggerFactory.getLogger(OutboxLockRepository.class);

    private static final LockOptions PESSIMISTIC_NOWAIT =
            new LockOptions(LockMode.PESSIMISTIC_WRITE).setTimeOut(LockOptions.NO_WAIT);

    private OutboxSessionFactory sessionFactory;

    public boolean acquireOrRefreshLock(String ownerId, Duration timeout) {

        // manually manage the session and transaction, because with declarative session/transaction management,
        // it was not possible to cleanly deal with the expected case that a lock was created concurrently, causing
        // a ConstraintViolationException - this either was thrown to the user, or, if caught, hibernate complained
        // about invalid session/transaction state.
        Transaction tx = null;
        OutboxLock lock = null;
        Session session = sessionFactory.openSession();
        try {
            tx = session.beginTransaction();

            lock = session.get(OutboxLock.class, OutboxLock.OUTBOX_LOCK_ID);
            if (lock == null) {
                logger.debug("No outbox lock found. Creating one for {}", ownerId);
                lock = new OutboxLock(ownerId, now().plus(timeout));
            } else if (ownerId.equals(lock.getOwnerId())) {
                logger.debug("Found outbox lock with requested owner {}, valid until {} - updating lock", lock.getOwnerId(), lock.getValidUntil());
                session.buildLockRequest(PESSIMISTIC_NOWAIT).lock(lock);
                lock.setValidUntil(now().plus(timeout));
            } else if (!ownerId.equals(lock.getOwnerId()) && lock.getValidUntil().isAfter(now())) {
                logger.debug("Found outbox lock with owner {}, valid until {}", lock.getOwnerId(), lock.getValidUntil());
                return false;
            } else {
                logger.info("Found expired outbox lock with owner {}, which was valid until {} - grabbing lock for {}", lock.getOwnerId(), lock.getValidUntil(), ownerId);
                session.buildLockRequest(PESSIMISTIC_NOWAIT).lock(lock);
                lock.setOwnerId(ownerId);
                lock.setValidUntil(now().plus(timeout));
            }

            session.persist(lock);
            session.flush();
            tx.commit();
            logger.info("Acquired or refreshed outbox lock for owner {}, valid until {}", ownerId, lock.getValidUntil());
            return true;
        } catch (LockingStrategyException e) {
            return handleException(e, ownerId, lock, tx);
        } catch (Throwable e) {
            if (e.getCause() instanceof ConstraintViolationException)
                return handleException((ConstraintViolationException) e.getCause(), ownerId, tx);
            else if (e.getCause() instanceof LockingStrategyException)
                return handleException((LockingStrategyException) e.getCause(), ownerId, lock, tx);
            else {
                logger.warn("Outbox lock selection/acquisition for owner {} failed", ownerId, e);
                tryRollback(tx);
                throw e;
            }
        } finally {
            session.close();
        }
    }

    private boolean handleException(LockingStrategyException e, String ownerId, OutboxLock lock, Transaction tx) {
        String reason = e.getCause() != null ? e.getCause().toString() : e.toString();
        logger.info("Could not grab lock {} for owner {} - database row is locked: {}", lock, ownerId, reason);
        tryRollback(tx);
        return false;
    }

    private boolean handleException(ConstraintViolationException e, String ownerId, Transaction tx) {
        String reason = e.getCause() != null ? e.getCause().toString() : e.toString();
        logger.info("Outbox lock for owner {} could not be created, another one has been created concurrently: {}", ownerId, reason);
        tryRollback(tx);
        return false;
    }

    private void tryRollback(Transaction tx) {
        if (tx != null)
            try {
                tx.rollback();
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
        Optional<OutboxLock> lock = queryByOwnerId(sessionFactory.getCurrentSession(), ownerId)
                .setLockMode(LockModeType.PESSIMISTIC_READ)
                .uniqueResultOptional();
        return lock.isPresent();
    }

    @Transactional
    public void releaseLock(String ownerId) {
        Session session = sessionFactory.getCurrentSession();
        queryByOwnerId(session, ownerId)
                .uniqueResultOptional()
                .ifPresentOrElse(lock -> {
                            session.delete(lock);
                            session.flush();
                            logger.info("Released outbox lock for owner {}", ownerId);
                        },
                        () -> logger.debug("Outbox lock for owner {} not found", ownerId)
                );
    }

    private Query<OutboxLock> queryByOwnerId(Session session, String ownerId) {
        return session
                .createQuery("FROM OutboxLock WHERE ownerId = :ownerId", OutboxLock.class)
                .setParameter("ownerId", ownerId);
    }

}
