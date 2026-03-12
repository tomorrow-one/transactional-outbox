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
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static one.tomorrow.transactionaloutbox.TestUtils.newHeaders;
import static one.tomorrow.transactionaloutbox.TestUtils.newRecord;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class OutboxRepositoryIntegrationTest {

    @Inject
    OutboxRepository repository;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    @Transactional
    void cleanUp() {
        entityManager
                .createQuery("DELETE FROM OutboxRecord")
                .executeUpdate();
    }

    @Test
    @Transactional
    void should_FindUnprocessedRecords() {
        // given
        OutboxRecord record1 = newRecord(Instant.now(), "topic1", "key1", "value1", newHeaders("h1", "v1"));
        repository.persist(record1);

        OutboxRecord record2 = newRecord("topic2", "key2", "value2", newHeaders("h2", "v2"));
        repository.persist(record2);

        // when
        List<OutboxRecord> result = repository.getUnprocessedRecords(100);

        // then
        assertEquals(1, result.size());
        OutboxRecord foundRecord = result.get(0);
        assertEquals(record2.getId(), foundRecord.getId());
        assertEquals(record2.getTopic(), foundRecord.getTopic());
        assertEquals(record2.getKey(), foundRecord.getKey());
    }

    @Test
    @Transactional
    void should_DeleteProcessedRecordsAfterRetentionTime() {
        // given
        OutboxRecord shouldBeKeptAsNotProcessed = newRecord(null, "topic1", "key1", "value1", Collections.emptyMap());
        repository.persist(shouldBeKeptAsNotProcessed);

        OutboxRecord shouldBeKeptAsNotInDeletionPeriod = newRecord(Instant.now().minus(Duration.ofDays(1)), "topic1", "key1", "value3", Collections.emptyMap());
        repository.persist(shouldBeKeptAsNotInDeletionPeriod);

        OutboxRecord shouldBeDeleted1 = newRecord(Instant.now().minus(Duration.ofDays(16)), "topic1", "key1", "value1", Collections.emptyMap());
        repository.persist(shouldBeDeleted1);

        OutboxRecord shouldBeDeleted2 = newRecord(Instant.now().minus(Duration.ofDays(18)), "topic1", "key1", "value2", Collections.emptyMap());
        repository.persist(shouldBeDeleted2);

        OutboxRecord shouldBeDeleted3 = newRecord(Instant.now().minus(Duration.ofDays(150)), "topic1", "key1", "value2", Collections.emptyMap());
        repository.persist(shouldBeDeleted3);

        // when
        Integer result = repository.deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(Instant.now().minus(Duration.ofDays(15)));

        // then
        assertEquals(3, result);
        assertFalse(outboxRecordExists(shouldBeDeleted1.getId()));
        assertFalse(outboxRecordExists(shouldBeDeleted2.getId()));
        assertFalse(outboxRecordExists(shouldBeDeleted3.getId()));
        assertTrue(outboxRecordExists(shouldBeKeptAsNotInDeletionPeriod.getId()));
        assertTrue(outboxRecordExists(shouldBeKeptAsNotProcessed.getId()));
    }

    private boolean outboxRecordExists(Long id) {
        Long result = (Long) entityManager.createQuery("select count(*) from OutboxRecord or where or.id=:id")
                .setParameter("id", id)
                .getSingleResult();
        return result > 0;
    }
}
