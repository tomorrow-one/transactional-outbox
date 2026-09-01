/**
 * Copyright 2025 Tomorrow GmbH @ https://tomorrow.one
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package one.tomorrow.transactionaloutbox.repository;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.stream.IntStream.range;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OutboxLockRepositoryIntegrationTest {

    @Inject
    OutboxLockRepository repository;

    @Inject
    EntityManager entityManager;

    @AfterEach
    @Transactional
    void cleanUp() {
        OutboxLock lock = entityManager.find(OutboxLock.class, OutboxLock.OUTBOX_LOCK_ID);
        if (lock != null) {
            entityManager.remove(lock);
        }
    }

    @Test
    void should_AcquireSingleLockOnly() throws Exception {
        // given
        List<String> ownerIds = range(0, 10).mapToObj(i -> "owner-" + i).toList();
        Duration timeout = Duration.ofSeconds(20); // this must be high enough to not produce flakiness with long gc pauses

        // executor, with warmup
        ExecutorService executor = Executors.newFixedThreadPool(ownerIds.size());
        executor.invokeAll(
                ownerIds.stream().map(ownerId -> (Callable<Void>) () -> null).toList()
        );

        // when
        List<Callable<Boolean>> acquireLockCalls = ownerIds.stream().map(ownerId ->
                (Callable<Boolean>) () -> repository.acquireOrRefreshLock(ownerId, timeout)
        ).toList();
        List<Future<Boolean>> resultFutures = executor.invokeAll(acquireLockCalls);
        executor.shutdown(); // just simple cleanup, threads are still executed

        // then
        AtomicBoolean locked = new AtomicBoolean(false);
        resultFutures.forEach(resultFuture -> {
            try {
                Boolean lockResult = resultFuture.get();
                assertFalse(locked.get() && lockResult, "Only one lock should be acquired");
                if (!locked.get())
                    locked.set(lockResult);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(locked.get(), "At least one lock should be acquired");
    }

    @Test
    void should_RefreshLock() throws InterruptedException {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofMillis(200);

        boolean locked = repository.acquireOrRefreshLock(ownerId1, timeout);
        assertTrue(locked);

        // when
        Thread.sleep(timeout.toMillis() / 2);
        locked = repository.acquireOrRefreshLock(ownerId1, timeout);
        assertTrue(locked);

        // then
        Thread.sleep(timeout.toMillis() / 2);
        locked = repository.acquireOrRefreshLock(ownerId2, timeout);
        assertFalse(locked);
    }

    @Test
    void should_AcquireForeignLock_AfterTimeout() throws InterruptedException {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofMillis(100);

        boolean locked = repository.acquireOrRefreshLock(ownerId1, timeout);
        assertTrue(locked);

        // when
        Thread.sleep(timeout.toMillis() + 10); // added a little buffer to ensure timeout has passed

        // then
        locked = repository.acquireOrRefreshLock(ownerId2, timeout);
        assertTrue(locked);

        locked = repository.acquireOrRefreshLock(ownerId1, timeout);
        assertFalse(locked);
    }

    @Test
    void should_ReleaseLock() {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofSeconds(10);

        boolean locked = repository.acquireOrRefreshLock(ownerId1, timeout);
        assertTrue(locked);

        // when
        repository.releaseLock(ownerId1);

        // then
        locked = repository.acquireOrRefreshLock(ownerId2, timeout);
        assertTrue(locked);
    }
}
