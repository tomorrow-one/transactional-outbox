package one.tomorrow.transactionaloutbox.repository;

import one.tomorrow.transactionaloutbox.AbstractIntegrationTest;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import org.flywaydb.test.annotation.FlywayTest;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.Thread.sleep;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@FlywayTest
@SuppressWarnings("ConstantConditions")
class OutboxLockRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxLockRepository testee;
    @Autowired
    private R2dbcEntityTemplate template;

    @AfterEach
    public void cleanUp() {
        template.delete(OutboxLock.class).all().block();
    }

    @Test
    void should_AcquireSingleLockOnly() throws InterruptedException {

        // given
        List<String> ownerIds = range(0, 20).mapToObj(i -> "owner-" + i).collect(toList());
        Duration timeout = Duration.ofSeconds(20); // this must be high enough to not produce flakyness with long gc pauses

        // executor, with warmup
        ExecutorService executor = Executors.newFixedThreadPool(ownerIds.size());
        executor.invokeAll(
                ownerIds.stream().map(ownerId -> (Callable<Void>) () -> null).collect(toList())
        );

        // when
        List<Callable<Boolean>> acquireLockCalls = ownerIds.stream().map(ownerId ->
                (Callable<Boolean>) () -> testee.acquireOrRefreshLock(ownerId, timeout).block()
        ).collect(toList());
        List<Future<Boolean>> resultFutures = executor.invokeAll(acquireLockCalls);
        executor.shutdown(); // just simple cleanup, threads are still executed

        // then
        AtomicBoolean locked = new AtomicBoolean(false);
        resultFutures.forEach(resultFuture -> {
            try {
                Boolean lockResult = resultFuture.get();
                assertThat(locked.get() && lockResult, is(false));
                if (!locked.get())
                    locked.set(lockResult);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(locked.get(), is(true));

    }

    @Test
    void should_RefreshLock() throws InterruptedException {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofMillis(200);

        boolean locked = testee.acquireOrRefreshLock(ownerId1, timeout).block();
        assertThat(locked, is(true));

        // when
        sleep(timeout.toMillis() / 2);
        locked = testee.acquireOrRefreshLock(ownerId1, timeout).block();
        assertThat(locked, is(true));

        // then
        sleep(timeout.toMillis() / 2);
        locked = testee.acquireOrRefreshLock(ownerId2, timeout).block();
        assertThat(locked, is(false));
    }

    @Test
    void should_AcquireForeignLock_AfterTimeout() throws InterruptedException {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofMillis(100);

        boolean locked = testee.acquireOrRefreshLock(ownerId1, timeout).block();
        assertThat(locked, is(true));

        // when
        sleep(timeout.toMillis() + 1);

        // then
        locked = testee.acquireOrRefreshLock(ownerId2, timeout).block();
        assertThat(locked, is(true));

        locked = testee.acquireOrRefreshLock(ownerId1, timeout).block();
        assertThat(locked, is(false));
    }

    @Test
    void should_ReleaseLock() {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofSeconds(10);

        boolean locked = testee.acquireOrRefreshLock(ownerId1, timeout).block();
        assertThat(locked, is(true));

        // when
        testee.releaseLock(ownerId1).block();

        // then
        locked = testee.acquireOrRefreshLock(ownerId2, timeout).block();
        assertThat(locked, is(true));
    }

}
