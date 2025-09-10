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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;

import java.time.Duration;

/**
 * Service for acquiring and refreshing a lock for the outbox processor.
 */
@ApplicationScoped
public class OutboxLockService {

    private final OutboxLockRepository repository;
    private final Duration lockTimeout;

    @Inject
    public OutboxLockService(
            OutboxLockRepository repository,
            TransactionalOutboxConfig config) {
        this.repository = repository;
        this.lockTimeout = config.lockTimeout();
    }

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

    Duration getLockTimeout() {
        return lockTimeout;
    }

}
