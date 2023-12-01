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

import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Repository
public interface OutboxRepository extends ReactiveCrudRepository<OutboxRecord, Long> {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    default Mono<OutboxRecord> saveInNewTransaction(OutboxRecord entity) {
        return save(entity);
    }

    /**
     * Return all records that have not yet been processed (i.e. that do not have the "processed" timestamp set).
     *
     * @param limit the max number of records to return
     * @return the requested records, sorted by id ascending
     */
    @Query("select * from outbox_kafka where processed is null order by id asc limit :limit")
    Flux<OutboxRecord> getUnprocessedRecords(int limit);

    Mono<Long> deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(Instant deleteOlderThan);

}
