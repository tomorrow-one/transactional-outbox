package one.tomorrow.transactionaloutbox.service;

import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@AllArgsConstructor
public class OutboxLockService {

	private final OutboxLockRepository repository;
	@Getter
	private final Duration lockTimeout;

	public boolean acquireOrRefreshLock(String ownerId) {
		return repository.acquireOrRefreshLock(ownerId, lockTimeout);
	}

	public void releaseLock(String ownerId) {
		repository.releaseLock(ownerId);
	}

	@Transactional
	public boolean runWithLock(String ownerId, Runnable action) {
		boolean outboxLockIsPreventedFromLockStealing = repository.preventLockStealing(ownerId);
		if (outboxLockIsPreventedFromLockStealing) {
			action.run();
		}
		return outboxLockIsPreventedFromLockStealing;
	}

}
