package one.tomorrow.transactionaloutbox.service;

import one.tomorrow.transactionaloutbox.repository.LockRepository;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@AllArgsConstructor
public class LockService {

	@Autowired
	private LockRepository repository;
	@Getter
	@Autowired @Qualifier("outboxLockTimeout")
	private Duration lockTimeout;

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
