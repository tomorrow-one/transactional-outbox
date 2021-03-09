package one.tomorrow.transactionaloutbox.repository;

import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface OutboxRepository extends ReactiveCrudRepository<OutboxRecord, Long> {

    /**
     * Return all records that have not yet been processed (i.e. that do not have the "processed" timestamp set).
     *
     * @param limit the max number of records to return
     * @return the requested records, sorted by id ascending
     */
    @Query("select * from outbox_kafka where processed is null order by id asc limit :limit")
    Flux<OutboxRecord> getUnprocessedRecords(int limit);

}
