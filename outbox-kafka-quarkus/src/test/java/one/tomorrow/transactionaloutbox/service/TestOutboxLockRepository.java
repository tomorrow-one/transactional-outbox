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
package one.tomorrow.transactionaloutbox.service;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@Alternative
@Priority(1)
@Singleton
public class TestOutboxLockRepository extends OutboxLockRepository {

    private final AtomicBoolean failAcquireOrRefreshLock = new AtomicBoolean(false);
    private final CountDownLatch acquireOrRefreshLockCDL = new CountDownLatch(1);

    private final AtomicBoolean failPreventLockStealing = new AtomicBoolean(false);
    private final CountDownLatch preventLockStealingCDL = new CountDownLatch(1);

    public TestOutboxLockRepository(EntityManager entityManager) {
        super(entityManager);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean acquireOrRefreshLock(String ownerId, Duration timeout) {
        if (failAcquireOrRefreshLock.get()) {
            acquireOrRefreshLockCDL.countDown();
            throw new RuntimeException("Simulated exception");
        }
        return super.acquireOrRefreshLock(ownerId, timeout);
    }

    @Override
    public boolean preventLockStealing(String ownerId) {
        if (failPreventLockStealing.get()) {
            preventLockStealingCDL.countDown();
            throw new RuntimeException("Simulated exception");
        }
        return super.preventLockStealing(ownerId);
    }

    public AtomicBoolean failAcquireOrRefreshLock() {
        return failAcquireOrRefreshLock;
    }

    public CountDownLatch acquireOrRefreshLockCDL() {
        return acquireOrRefreshLockCDL;
    }

    public AtomicBoolean failPreventLockStealing() {
        return failPreventLockStealing;
    }

    public CountDownLatch preventLockStealingCDL() {
        return preventLockStealingCDL;
    }

}
