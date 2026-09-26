/**
 * Copyright 2022-2023 Tomorrow GmbH @ https://tomorrow.one
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

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(MySqlTestProfile.class)
class MySqlDatabaseCompatibilityIntegrationTest extends OutboxRepositoryIntegrationTest {
    @Inject
    OutboxLockRepository locks;

    @Test
    void roundTripsJsonAndLargeBinaryPayload() {
        byte[] payload = new byte[100_000];
        payload[0] = (byte) 255;
        Map<String, String> headers = Map.of("한글", "value 🐬");
        OutboxRecord record = OutboxRecord.builder().topic("topic").key("key").value(payload).headers(headers).build();
        QuarkusTransaction.requiringNew().run(() -> repository.persist(record));
        QuarkusTransaction.requiringNew().run(() -> {
            var found = repository.getUnprocessedRecords(1).get(0);
            assertEquals(record.getId(), found.getId());
            assertEquals("key", found.getKey());
            assertEquals(headers, found.getHeaders());
            assertArrayEquals(payload, found.getValue());
        });
    }

    @Test
    void rollsBackOutboxWithCallerTransaction() {
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            repository.persist(OutboxRecord.builder().topic("topic").value(new byte[]{1}).build());
            throw new IllegalStateException("rollback");
        }));
        assertTrue(repository.getUnprocessedRecords(10).isEmpty());
    }

    @Test
    void protectsAndTransfersExpiredLock() {
        Duration timeout = Duration.ofSeconds(30);
        try {
            assertTrue(locks.acquireOrRefreshLock("Owner", timeout));
            assertTrue(locks.acquireOrRefreshLock("Owner", timeout));
            assertFalse(locks.acquireOrRefreshLock("owner", timeout));
            locks.releaseLock("owner");
            assertFalse(locks.acquireOrRefreshLock("other", timeout));
            QuarkusTransaction.requiringNew().run(() -> entityManager.createQuery(
                    "update OutboxLock set validUntil = :expired").setParameter("expired", Instant.now().minusSeconds(60))
                    .executeUpdate());
            QuarkusTransaction.requiringNew().run(() -> {
                assertTrue(locks.preventLockStealing("Owner"));
                assertFalse(locks.acquireOrRefreshLock("other", timeout));
            });
            assertTrue(locks.acquireOrRefreshLock("other", timeout));
            assertFalse(locks.acquireOrRefreshLock("Owner", timeout));
        } finally {
            locks.releaseLock("Owner");
            locks.releaseLock("other");
        }
    }
}
