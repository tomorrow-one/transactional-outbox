package one.tomorrow.transactionaloutbox.reactive.service;

import one.tomorrow.transactionaloutbox.reactive.AbstractIntegrationTest;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxLockRepository;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.*;

import reactor.test.StepVerifier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@FlywayTest
@SuppressWarnings({"unused", "ConstantConditions"})
class OutboxLockServiceIntegrationTest extends AbstractIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxLockServiceIntegrationTest.class);

    @Autowired
    private OutboxLockRepository lockRepository;
    @Autowired
    private TransactionalOperator rxtx;

    private static ExecutorService executorService;

    @BeforeAll
    public static void beforeClass() {
        executorService = Executors.newCachedThreadPool();
    }

    @AfterAll
    public static void afterClass() {
        executorService.shutdown();
    }

    @Test
    void runWithLock_should_preventLockStealing() throws ExecutionException, InterruptedException, TimeoutException {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration lockTimeout = Duration.ZERO;
        OutboxLockService lockService = new OutboxLockService(lockRepository, rxtx);

        boolean locked = lockService.acquireOrRefreshLock(ownerId1, lockTimeout).block();
        assertThat(locked, is(true));

        CyclicBarrier barrier1 = new CyclicBarrier(2);

        // use CompletableFuture + Mono, because blocking on Bariers inside deferred monos would block the IO thread
        // and in consequence the app would be locked (and test fail)
        CompletableFuture<Void> barrier2CompletionStage = new CompletableFuture<>();
        Mono<Void> barrier2Mono = Mono.fromCompletionStage(barrier2CompletionStage);

        CompletableFuture<Void> barrier3CompletionStage = new CompletableFuture<>();
        Mono<Void> barrier3Mono = Mono.fromCompletionStage(barrier3CompletionStage);

        // when
        Future<Boolean> runWithLockResult = executorService.submit(() -> {
            await(barrier1);
            return lockService.runWithLock(ownerId1, Mono.defer(() -> {
                barrier2CompletionStage.complete(null); // start owner2 acquireOrRefreshLock not before owner1 is inside "runWithLock"
                return barrier3Mono;
            })).block();
        });
        Future<Boolean> lockStealingAttemptResult = executorService.submit(() -> {
            await(barrier1);
            barrier2Mono.block(); // start acquireOrRefreshLock not before owner1 is inside "runWithLock"
            boolean result = lockService.acquireOrRefreshLock(ownerId2, lockTimeout).block();
            barrier3CompletionStage.complete(null); // let owner1 runWithLock action complete
            return result;
        });

        // then
        assertThat(runWithLockResult.get(5, SECONDS), is(true));
        assertThat(lockStealingAttemptResult.get(5, SECONDS), is(false));
    }

    @Test
    void runWithLock_should_returnError_from_callback() throws ExecutionException, InterruptedException, TimeoutException {
        // given
        String ownerId = "owner-1";
        OutboxLockService lockService = new OutboxLockService(lockRepository, rxtx);
        boolean locked = lockService.acquireOrRefreshLock(ownerId, Duration.ZERO).block();
        assertThat(locked, is(true));

        // when
        Mono<Boolean> runWithLockResult = lockService.runWithLock(ownerId, Mono.error(new RuntimeException("simulated error")));

        // then
        StepVerifier.create(runWithLockResult)
                .expectErrorMatches(e -> e instanceof RuntimeException && e.getMessage().equals("simulated error"))
                .verify();
    }

    /** Awaits the given barrier, turning checked exceptions into unchecked, for easier usage in lambdas. */
    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
