package one.tomorrow.transactionaloutbox.repository;

import lombok.AllArgsConstructor;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.stereotype.Component;


@Component
@AllArgsConstructor
public class LegacyOutboxSessionFactory implements OutboxSessionFactory {

    private final SessionFactory sessionFactory;

    @Override
    public Session getCurrentSession() {
        return sessionFactory.getCurrentSession();
    }

    @Override
    public Session openSession() {
        return sessionFactory.openSession();
    }
}