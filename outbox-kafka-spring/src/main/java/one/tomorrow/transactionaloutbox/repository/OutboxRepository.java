package one.tomorrow.transactionaloutbox.repository;

import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.hibernate.SessionFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class OutboxRepository {

    private SessionFactory sessionFactory;

    public OutboxRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public void persist(OutboxRecord record) {
        sessionFactory.getCurrentSession().persist(record);
    }

    @Transactional
    public void update(OutboxRecord record) {
        sessionFactory.getCurrentSession().update(record);
    }

    /**
     * Return all records that have not yet been processed (i.e. that do not have the "processed" timestamp set).
     *
     * @param limit the max number of records to return
     * @return the requested records, sorted by id ascending
     */
    @Transactional
    public List<OutboxRecord> getUnprocessedRecords(int limit) {
        return sessionFactory.getCurrentSession()
                .createQuery("FROM OutboxRecord WHERE processed IS NULL ORDER BY id ASC", OutboxRecord.class)
                .setMaxResults(limit)
                .getResultList();
    }

}
