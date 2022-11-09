/**
 * Copyright 2022 Tomorrow GmbH @ https://tomorrow.one
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

import one.tomorrow.transactionaloutbox.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {
        LegacyOutboxSessionFactory.class,
        OutboxLockRepository.class,
        OutboxLock.class,
        IntegrationTestConfig.class
})
@TestExecutionListeners({
        DependencyInjectionTestExecutionListener.class,
        FlywayTestExecutionListener.class
})
@FlywayTest
public class OutboxLockRepositoryIntegrationTest {

    @Autowired
    private OutboxLockRepository testee;
    @Autowired
    private SessionFactory sessionFactory;

    @After
    public void cleanUp() {
        try (Session session = sessionFactory.openSession()) {
            Transaction tx = session.beginTransaction();
            OutboxLock lock = session.get(OutboxLock.class, OutboxLock.OUTBOX_LOCK_ID);
            if(lock != null) session.delete(lock);
            tx.commit();
        }
    }

    @Test
    public void should_AcquireSingleLockOnly() throws InterruptedException {

        // given
        List<String> ownerIds = range(0, 10).mapToObj(i -> "owner-" + i).collect(toList());
        Duration timeout = Duration.ofSeconds(20); // this must be high enough to not produce flakyness with long gc pauses

        // executor, with warmup
        ExecutorService executor = Executors.newFixedThreadPool(ownerIds.size());
        executor.invokeAll(
                ownerIds.stream().map(ownerId -> (Callable<Void>) () -> null).collect(toList())
        );

        // when
        List<Callable<Boolean>> acquireLockCalls = ownerIds.stream().map(ownerId ->
                (Callable<Boolean>) () -> testee.acquireOrRefreshLock(ownerId, timeout)
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
    public void should_RefreshLock() throws InterruptedException {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofMillis(200);

        boolean locked = testee.acquireOrRefreshLock(ownerId1, timeout);
        assertTrue(locked);

        // when
        Thread.sleep(timeout.toMillis() / 2);
        locked = testee.acquireOrRefreshLock(ownerId1, timeout);
        assertTrue(locked);

        // then
        Thread.sleep(timeout.toMillis() / 2);
        locked = testee.acquireOrRefreshLock(ownerId2, timeout);
        assertFalse(locked);
    }

    @Test
    public void should_AcquireForeignLock_AfterTimeout() throws InterruptedException {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofMillis(100);

        boolean locked = testee.acquireOrRefreshLock(ownerId1, timeout);
        assertTrue(locked);

        // when
        Thread.sleep(timeout.toMillis() + 1);

        // then
        locked = testee.acquireOrRefreshLock(ownerId2, timeout);
        assertTrue(locked);

        locked = testee.acquireOrRefreshLock(ownerId1, timeout);
        assertFalse(locked);
    }

    @Test
    public void should_ReleaseLock() {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofSeconds(10);

        boolean locked = testee.acquireOrRefreshLock(ownerId1, timeout);
        assertTrue(locked);

        // when
        testee.releaseLock(ownerId1);

        // then
        locked = testee.acquireOrRefreshLock(ownerId2, timeout);
        assertTrue(locked);
    }

}
