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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
@AllArgsConstructor
public class OutboxRepository {

    private final EntityManager entityManager;

    /**
     * Persist a new outbox record
     * @param record the record to persist
     */
    public void persist(OutboxRecord record) {
        entityManager.persist(record);
    }

    /**
     * Update an existing outbox record
     * @param record the record to update
     */
    @Transactional
    public void update(OutboxRecord record) {
        entityManager.merge(record);
        entityManager.flush();
    }

    /**
     * Return all records that have not yet been processed (i.e. that do not have the "processed" timestamp set).
     *
     * @param limit the max number of records to return
     * @return the requested records, sorted by id ascending
     */
    @Transactional
    public List<OutboxRecord> getUnprocessedRecords(int limit) {
        return entityManager
                .createQuery("FROM OutboxRecord WHERE processed IS NULL ORDER BY id ASC", OutboxRecord.class)
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * Delete processed records older than defined point in time
     *
     * @param deleteOlderThan the point in time until the processed entities shall be kept
     * @return amount of deleted rows
     */
    @Transactional
    public int deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(Instant deleteOlderThan) {
        return entityManager
                .createQuery("DELETE FROM OutboxRecord or WHERE or.processed IS NOT NULL AND or.processed < :deleteOlderThan")
                .setParameter("deleteOlderThan", deleteOlderThan)
                .executeUpdate();
    }
}
