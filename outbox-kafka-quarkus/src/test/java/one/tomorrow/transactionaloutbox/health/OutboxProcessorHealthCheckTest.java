/**
 * Copyright 2025-2026 Tomorrow GmbH @ https://tomorrow.one
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

import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.service.OutboxProcessor;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static one.tomorrow.transactionaloutbox.config.TestTransactionalOutboxConfig.createConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Health policy tests must not race a running processor that consumes their fixture records. */
class OutboxProcessorHealthCheckTest {
    private OutboxProcessor processor;
    private OutboxRepository repository;
    private OutboxProcessorHealthCheck healthCheck;

    @BeforeEach
    void setUp() {
        processor = mock(OutboxProcessor.class);
        repository = mock(OutboxRepository.class);
        when(processor.isEnabled()).thenReturn(true);
        when(processor.getLockOwnerId()).thenReturn("health-check-test");
        // Evaluate the timestamp when queried, independent of CI startup/scheduling delays.
        when(processor.getLastLockAcquisitionAttempt()).thenAnswer(ignored -> Instant.now());
        when(repository.getUnprocessedRecords(1)).thenReturn(List.of());
        healthCheck = new OutboxProcessorHealthCheck(processor, repository,
                createConfig(Duration.ofMillis(100), Duration.ofMillis(500), "health-check-test", "health-test"));
    }

    @Test
    void shouldReturnHealthyWhenProcessorIsEnabled() {
        HealthCheckResponse response = healthCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("transactional-outbox-processor", response.getName());
        Map<String, Object> data = response.getData().orElseThrow();
        assertEquals(true, data.get("enabled"));
        assertEquals("health-check-test", data.get("lockOwnerId"));
        assertEquals(false, data.get("active"));
        assertEquals(false, data.get("closed"));
        assertEquals("inactive", data.get("status"));
    }

    @Test
    void shouldIncludeOldestMessageAgeWhenMessagesExist() {
        // An inactive instance is healthy even when another instance has an old pending message.
        oldestMessage(Duration.ofSeconds(30));
        HealthCheckResponse response = healthCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        Map<String, Object> data = response.getData().orElseThrow();
        assertTrue(Duration.parse((String) data.get("oldestUnprocessedMessageAge"))
                .compareTo(Duration.ofSeconds(30)) >= 0);
        assertNotNull(data.get("oldestUnprocessedMessageCreated"));
        verify(repository).getUnprocessedRecords(1);
    }

    @Test
    void shouldReturnDownWhenActiveProcessorHasStaleMessage() {
        when(processor.isActive()).thenReturn(true);
        oldestMessage(Duration.ofSeconds(30));
        assertStatus(HealthCheckResponse.Status.DOWN, "processing-stalled");
    }

    @Test
    void shouldReturnUpWhenActiveProcessorHasFreshMessage() {
        when(processor.isActive()).thenReturn(true);
        oldestMessage(Duration.ZERO);
        assertStatus(HealthCheckResponse.Status.UP, "active");
    }

    @Test
    void shouldReturnNoneWhenNoUnprocessedMessages() {
        HealthCheckResponse response = healthCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("none", response.getData().orElseThrow().get("oldestUnprocessedMessageAge"));
    }

    @Test
    void shouldIncludeLockInformation() {
        HealthCheckResponse response = healthCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        Map<String, Object> data = response.getData().orElseThrow();
        assertEquals("health-check-test", data.get("lockOwnerId"));
        assertNotNull(Instant.parse((String) data.get("lastLockAttempt")));
    }

    @Test
    void shouldHandleRepositoryErrors() {
        when(repository.getUnprocessedRecords(1)).thenThrow(new IllegalStateException("database unavailable"));
        // The existing policy tolerates failure to read the oldest message.
        assertStatus(HealthCheckResponse.Status.UP, "inactive");
        verify(repository).getUnprocessedRecords(1);
    }

    @Test
    void shouldReturnDownWhenLockAcquisitionIsStale() {
        when(processor.getLastLockAcquisitionAttempt()).thenReturn(Instant.EPOCH);
        assertStatus(HealthCheckResponse.Status.DOWN, "stale");
        verifyNoInteractions(repository);
    }

    @Test
    void shouldReturnUpBeforeFirstLockAttempt() {
        when(processor.getLastLockAcquisitionAttempt()).thenReturn(null);
        HealthCheckResponse response = healthCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("never", response.getData().orElseThrow().get("lastLockAttempt"));
    }

    @Test
    void shouldReturnDownWhenProcessorIsClosed() {
        when(processor.isClosed()).thenReturn(true);
        assertStatus(HealthCheckResponse.Status.DOWN, "closed");
        verifyNoInteractions(repository);
    }

    @Test
    void shouldReturnUpWhenProcessorIsDisabled() {
        when(processor.isEnabled()).thenReturn(false);
        when(processor.isClosed()).thenReturn(true);
        assertStatus(HealthCheckResponse.Status.UP, "disabled");
        verifyNoInteractions(repository);
    }

    private void oldestMessage(Duration age) {
        OutboxRecord record = mock(OutboxRecord.class);
        when(record.getCreated()).thenAnswer(ignored -> Instant.now().minus(age));
        when(repository.getUnprocessedRecords(1)).thenReturn(List.of(record));
    }

    private void assertStatus(HealthCheckResponse.Status expected, String detail) {
        HealthCheckResponse response = healthCheck.call();
        assertEquals(expected, response.getStatus(), () -> response.getData().toString());
        assertEquals(detail, response.getData().orElseThrow().get("status"));
    }
}
