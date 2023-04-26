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

import lombok.AllArgsConstructor;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@AllArgsConstructor
public class OutboxRepository {

    private final OutboxSessionFactory sessionFactory;

    public void persist(OutboxRecord record) {
        sessionFactory.getCurrentSession().persist(record);
    }

    @Transactional
    public void update(OutboxRecord record) {
        sessionFactory.getCurrentSession().merge(record);
    }

    /**
     * Return all records that have not yet been processed (i.e. that do not have the "processed" timestamp set).
     *
     * @param limit the max number of records to return
     * @return the requested records, sorted by id ascending
     */
    @Transactional
    public List<OutboxRecord> getUnprocessedRecords(int limit) {
        return sessionFactory.getCurrentSession()
                .createQuery("FROM OutboxRecord WHERE processed IS NULL ORDER BY id ASC", OutboxRecord.class)
                .setMaxResults(limit)
                .getResultList();
    }

}
