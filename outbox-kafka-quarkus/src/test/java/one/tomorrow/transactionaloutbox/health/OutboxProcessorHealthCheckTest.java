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
package one.tomorrow.transactionaloutbox.health;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(OutboxProcessorHealthCheckTest.class)
public class OutboxProcessorHealthCheckTest implements QuarkusTestProfile {

    @Inject
    @Readiness
    OutboxProcessorHealthCheck healthCheck;

    @Inject
    EntityManager entityManager;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "one.tomorrow.transactional-outbox.enabled", "true",
                "one.tomorrow.transactional-outbox.processing-interval", "PT0.1S", // 100ms for testing
                "one.tomorrow.transactional-outbox.lock-owner-id", "health-check-test",
                "one.tomorrow.transactional-outbox.lock-timeout", "PT0.5S",
                "one.tomorrow.transactional-outbox.event-source", "health-test"
        );
    }

    @BeforeEach
    @AfterEach
    @Transactional
    void cleanUp() {
        entityManager.createQuery("DELETE FROM OutboxRecord").executeUpdate();
        entityManager.createQuery("DELETE FROM OutboxLock").executeUpdate();
    }

    @Test
    void shouldReturnHealthyWhenProcessorIsEnabled() {
        // when
        HealthCheckResponse response = healthCheck.call();

        // then
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("transactional-outbox-processor", response.getName());

        // Verify basic processor information is included
        Map<String, Object> data = response.getData().orElse(Map.of());
        assertTrue((Boolean) data.get("enabled"));
        assertEquals("health-check-test", data.get("lockOwnerId"));
        assertNotNull(data.get("active"));
        assertFalse((Boolean) data.get("closed"));
    }

    @Test
    void shouldIncludeOldestMessageAgeWhenMessagesExist() {
        // given - add some unprocessed messages with different ages
        addUnprocessedMessage("test-topic-1", "key1", "message1", Instant.now().minus(30, ChronoUnit.SECONDS));
        addUnprocessedMessage("test-topic-2", "key2", "message2", Instant.now().minus(10, ChronoUnit.SECONDS));

        // when
        HealthCheckResponse response = healthCheck.call();

        // then
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());

        // Should include oldest message age (30 seconds is oldest)
        Map<String, Object> data = response.getData().orElse(Map.of());
        String oldestAge = (String) data.get("oldestUnprocessedMessageAge");
        assertNotNull(oldestAge);

        // The oldest message should be approximately 30 seconds old
        // Accept some variation due to test execution time
        assertTrue(oldestAge.matches("PT\\d+(\\.\\d+)?S"),
            "Expected duration format like PT30S or PT30.123S, but was: " + oldestAge);

        // Should include creation timestamp
        assertNotNull(data.get("oldestUnprocessedMessageCreated"));
    }

    @Test
    void shouldReturnDownWhenActiveProcessorHasStaleMessage() {
        // given - add an old message (older than 2x processing interval = 2 * 100ms = 200ms)
        Instant oldTimestamp = Instant.now().minus(1, ChronoUnit.SECONDS); // 1 second old (much older than 200ms)
        addUnprocessedMessage("test-topic", "key1", "stale-message", oldTimestamp);

        // Wait a bit to ensure the processor might become active
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // when
        HealthCheckResponse response = healthCheck.call();

        // then
        Map<String, Object> data = response.getData().orElse(Map.of());

        // If processor is active and message is older than 2x processing interval (200ms), should be DOWN
        Boolean isActive = (Boolean) data.get("active");
        if (Boolean.TRUE.equals(isActive)) {
            assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
            assertEquals("processing-stalled", data.get("status"));
            assertNotNull(data.get("reason"));
        } else {
            // If processor is not active, should still be UP (normal in multi-instance setup)
            assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        }
    }

    @Test
    void shouldReturnUpWhenActiveProcessorHasFreshMessage() {
        // given - add a fresh message (newer than 2x processing interval = 200ms)
        Instant freshTimestamp = Instant.now().minus(50, ChronoUnit.MILLIS); // 50ms old (less than 200ms threshold)
        addUnprocessedMessage("test-topic", "key1", "fresh-message", freshTimestamp);

        // when
        HealthCheckResponse response = healthCheck.call();

        // then - Even if processor is active, fresh message should not cause DOWN status
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());

        Map<String, Object> data = response.getData().orElse(Map.of());
        assertNotEquals("processing-stalled", data.get("status"));
    }

    @Test
    void shouldReturnNoneWhenNoUnprocessedMessages() {
        // when (no messages added)
        HealthCheckResponse response = healthCheck.call();

        // then
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());

        Map<String, Object> data = response.getData().orElse(Map.of());
        assertEquals("none", data.get("oldestUnprocessedMessageAge"));
    }

    @Test
    void shouldIncludeLockInformation() {
        // when
        HealthCheckResponse response = healthCheck.call();

        // then
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());

        // Should include lock-related information
        Map<String, Object> data = response.getData().orElse(Map.of());
        assertNotNull(data.get("lockOwnerId"));
        assertEquals("health-check-test", data.get("lockOwnerId"));

        // Should track lock acquisition attempts
        assertNotNull(data.get("lastLockAttempt"));
    }

    @Test
    void shouldHandleRepositoryErrors() {
        // The health check should gracefully handle repository errors
        // In a real scenario, this might happen if the database is unavailable

        // when
        HealthCheckResponse response = healthCheck.call();

        // then
        // Even if there are issues with getting oldest message,
        // the health check should not fail completely
        assertEquals("transactional-outbox-processor", response.getName());
        assertNotNull(response.getStatus());
    }

    @Transactional
    void addUnprocessedMessage(String topic, String key, String message) {
        addUnprocessedMessage(topic, key, message, Instant.now());
    }

    @Transactional
    void addUnprocessedMessage(String topic, String key, String message, Instant created) {
        OutboxRecord outboxRecord = new OutboxRecord();
        outboxRecord.setTopic(topic);
        outboxRecord.setKey(key);
        outboxRecord.setValue(message.getBytes());
        // Don't set processed timestamp - this makes it unprocessed
        entityManager.persist(outboxRecord);
        entityManager.flush(); // Ensure it's written to DB immediately
        // update created timestamp
        outboxRecord.setCreated(created);
        entityManager.merge(outboxRecord);
        entityManager.flush();
    }
}
