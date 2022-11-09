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
package one.tomorrow.transactionaloutbox.reactive.repository;

import one.tomorrow.transactionaloutbox.reactive.AbstractIntegrationTest;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxLock;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.util.Pair;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.lang.Thread.sleep;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;
import static one.tomorrow.transactionaloutbox.reactive.TestUtils.randomBoolean;
import static one.tomorrow.transactionaloutbox.reactive.TestUtils.randomInt;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

@FlywayTest
@SuppressWarnings({"unused", "ConstantConditions", "OptionalUsedAsFieldOrParameterType"})
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
    void should_AcquireSingleLockOnly_WithoutExistingLock() throws InterruptedException {
        // given
        List<String> ownerIds = range(0, 100).mapToObj(i -> "owner-" + i).collect(toList());
        Duration timeout = Duration.ofSeconds(20); // this must be high enough to not produce flakyness with long gc pauses

        // when
        List<String> locks = acquireLockConcurrently(ownerIds, timeout, Optional.empty());

        // then
        assertThat(locks, hasSize(1));
    }

    @Test
    void should_RefreshLock_ForExistingLock() throws InterruptedException {

        // given
        List<String> ownerIds = range(0, 100).mapToObj(i -> "owner-" + i).collect(toList());
        Duration timeout = Duration.ofSeconds(20); // this must be high enough to not produce flakyness with long gc pauses

        String randomOwnerId = ownerIds.get(randomInt(ownerIds.size()));
        boolean locked = testee.acquireOrRefreshLock(randomOwnerId, timeout, randomBoolean()).block();
        assertThat(locked, is(true));

        // when
        List<String> locks = acquireLockConcurrently(ownerIds, timeout, Optional.of(randomOwnerId));

        // then
        assertThat(locks, contains(randomOwnerId));
    }

    @Test
    void should_RefreshLock() throws InterruptedException {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofMillis(200);

        boolean locked = testee.acquireOrRefreshLock(ownerId1, timeout, randomBoolean()).block();
        assertThat(locked, is(true));

        // when
        sleep(timeout.toMillis() / 2);
        locked = testee.acquireOrRefreshLock(ownerId1, timeout, randomBoolean()).block();
        assertThat(locked, is(true));

        // then
        sleep(timeout.toMillis() / 2);
        locked = testee.acquireOrRefreshLock(ownerId2, timeout, randomBoolean()).block();
        assertThat(locked, is(false));
    }

    @Test
    void should_AcquireForeignLock_AfterTimeout() throws InterruptedException {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofMillis(100);

        boolean locked = testee.acquireOrRefreshLock(ownerId1, timeout, randomBoolean()).block();
        assertThat(locked, is(true));

        // when
        sleep(timeout.toMillis() + 1);

        // then
        locked = testee.acquireOrRefreshLock(ownerId2, timeout, randomBoolean()).block();
        assertThat(locked, is(true));

        locked = testee.acquireOrRefreshLock(ownerId1, timeout, randomBoolean()).block();
        assertThat(locked, is(false));
    }

    @Test
    void should_PreventLockStealing() {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofSeconds(20);

        boolean locked = testee.acquireOrRefreshLock(ownerId1, timeout, randomBoolean()).block();
        assertThat(locked, is(true));

        // when
        boolean preventedLockStealing1 = testee.preventLockStealing(ownerId1).block();

        // then
        assertThat(preventedLockStealing1, is(true));

        // and when
        boolean preventedLockStealing2 = testee.preventLockStealing(ownerId2).block();

        // then
        assertThat(preventedLockStealing2, is(false));
    }

    @Test
    void should_ReleaseLock() {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        Duration timeout = Duration.ofSeconds(10);

        boolean locked = testee.acquireOrRefreshLock(ownerId1, timeout, randomBoolean()).block();
        assertThat(locked, is(true));

        // when
        testee.releaseLock(ownerId1).block();

        // then
        locked = testee.acquireOrRefreshLock(ownerId2, timeout, randomBoolean()).block();
        assertThat(locked, is(true));
    }

    private List<String> acquireLockConcurrently(List<String> ownerIds, Duration timeout, Optional<String> lockedOwnerId) throws InterruptedException {
        // executor, with warmup
        ExecutorService executor = Executors.newFixedThreadPool(ownerIds.size());
        executor.invokeAll(
                ownerIds.stream().map(ownerId -> (Callable<Void>) () -> null).collect(toList())
        );

        List<Callable<Pair<String, Boolean>>> acquireLockCalls = ownerIds.stream().map(ownerId ->
                (Callable<Pair<String, Boolean>>) () -> {
                    Boolean refreshLock = lockedOwnerId.map(ownerId::equals).orElse(false);
                    return Pair.of(ownerId, testee.acquireOrRefreshLock(ownerId, timeout, refreshLock).block());
                }
        ).collect(toList());
        List<Future<Pair<String, Boolean>>> resultFutures = executor.invokeAll(acquireLockCalls);
        executor.shutdown(); // just simple cleanup, threads are still executed

        return resultFutures.stream()
                .map(lockOwnerAndResultFuture -> {
                    try {
                        return lockOwnerAndResultFuture.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .filter(Pair::getSecond)
                .map(Pair::getFirst)
                .collect(toList());
    }

}
