package one.tomorrow.transactionaloutbox.service;

import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Helper class, which provides the transactional boundary for ${@link OutboxRepository#persist(OutboxRecord)}
 */
@Repository
public class TransactionalOutboxRepository {

	private OutboxRepository repository;

	public TransactionalOutboxRepository(OutboxRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public void persist(OutboxRecord record) {
		repository.persist(record);
	}

}
