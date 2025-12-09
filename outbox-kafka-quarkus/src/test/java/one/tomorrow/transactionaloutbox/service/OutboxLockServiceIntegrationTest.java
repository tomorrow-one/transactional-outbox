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
package one.tomorrow.transactionaloutbox.service;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@TestProfile(OutboxLockServiceIntegrationTest.class)
public class OutboxLockServiceIntegrationTest implements QuarkusTestProfile {

    @Inject
    OutboxLockService lockService;

    private static ExecutorService executorService;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("one.tomorrow.transactional-outbox.lock-timeout", "PT0S");
    }

    @BeforeAll
    static void beforeClass() {
        executorService = Executors.newCachedThreadPool();
    }

    @AfterAll
    static void afterClass() {
        executorService.shutdown();
    }

    @Test
    void should_RunWithLock_PreventLockStealing() throws ExecutionException, InterruptedException, TimeoutException {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";

        boolean locked = lockService.acquireOrRefreshLock(ownerId1);
        assumeTrue(locked);

        CyclicBarrier barrier1 = new CyclicBarrier(2);
        CyclicBarrier barrier2 = new CyclicBarrier(2);
        CyclicBarrier barrier3 = new CyclicBarrier(2);

        // when
        Future<Boolean> runWithLockResult = executorService.submit(() -> {
            await(barrier1);
            return lockService.runWithLock(ownerId1, () -> {
                await(barrier2);
                await(barrier3); // exit runWithLock not before owner2 has tried to "acquireOrRefreshLock"
            });
        });
        Future<Boolean> lockStealingAttemptResult = executorService.submit(() -> {
            await(barrier1);
            await(barrier2); // start acquireOrRefreshLock not before owner1 is inside "runWithLock"
            boolean result = lockService.acquireOrRefreshLock(ownerId2);
            await(barrier3);
            return result;
        });

        // then
        assertTrue(runWithLockResult.get(5, SECONDS));
        assertFalse(lockStealingAttemptResult.get(5, SECONDS));
    }

    /** Awaits the given barrier, turning checked exceptions into unchecked, for easier usage in lambdas. */
    private void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
