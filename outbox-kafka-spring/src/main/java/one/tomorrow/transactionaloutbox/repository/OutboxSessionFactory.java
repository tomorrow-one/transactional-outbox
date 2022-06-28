package one.tomorrow.transactionaloutbox.repository;

import org.hibernate.Session;

public interface OutboxSessionFactory {
    Session getCurrentSession();
    Session openSession();
}
