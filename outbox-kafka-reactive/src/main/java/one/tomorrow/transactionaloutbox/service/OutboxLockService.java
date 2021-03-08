package one.tomorrow.transactionaloutbox.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@AllArgsConstructor
public class OutboxLockService {

	private final OutboxLockRepository repository;
	private final TransactionalOperator rxtx;
	@Getter
	private final Duration lockTimeout;

	public Mono<Boolean> acquireOrRefreshLock(String ownerId) {
		return repository.acquireOrRefreshLock(ownerId, lockTimeout);
	}

	public Mono<Void> releaseLock(String ownerId) {
		return repository.releaseLock(ownerId);
	}

	@SuppressWarnings("java:S5411")
	public Mono<Boolean> runWithLock(String ownerId, Mono<Void> action) {
		return Mono.defer(() -> repository.preventLockStealing(ownerId).flatMap(outboxLockIsPreventedFromLockStealing ->
				outboxLockIsPreventedFromLockStealing
						? action.thenReturn(true)
						: Mono.just(false)
		)).as(rxtx::transactional);
	}

}
