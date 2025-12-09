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

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class OutboxRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final RowMapper<OutboxRecord> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp processed = rs.getTimestamp("processed");
        return new OutboxRecord(
                rs.getLong("id"),
                rs.getTimestamp("created"),
                processed == null ? null : processed.toInstant(),
                rs.getString("topic"),
                rs.getString("key"),
                rs.getBytes("value"),
                fromJson(rs.getString("headers"))
        );
    };

    private final JdbcTemplate jdbcTemplate;

    private final SimpleJdbcInsert jdbcInsert;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcInsert = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("outbox_kafka")
                .usingGeneratedKeyColumns("id");
    }

    public void persist(OutboxRecord record) {
        record.setCreated(new Timestamp(System.currentTimeMillis()));
        Long id = (Long) jdbcInsert.executeAndReturnKey(argsFor(record));
        record.setId(id);
    }

    private static Map<String, Object> argsFor(OutboxRecord record) {
        Map<String, Object> args = new HashMap<>();
        args.put("created", record.getCreated());
        if (record.getProcessed() != null)
            args.put("processed", Timestamp.from(record.getProcessed()));
        args.put("topic", record.getTopic());
        if (record.getKey() != null)
            args.put("key", record.getKey());
        args.put("value", record.getValue());
        args.put("headers", toJson(record.getHeaders()));
        return args;
    }

    @Transactional
    public void updateProcessed(Long id, Instant processed) {
        jdbcTemplate.update("update outbox_kafka set processed = ? where id = ?", Timestamp.from(processed), id);
    }

    /**
     * Return all records that have not yet been processed (i.e. that do not have the "processed" timestamp set).
     *
     * @param limit the max number of records to return
     * @return the requested records, sorted by id ascending
     */
    public List<OutboxRecord> getUnprocessedRecords(int limit) {
        return jdbcTemplate.query("select * from outbox_kafka where processed is null order by id asc limit " + limit, ROW_MAPPER);
    }

    private static Map<String, String> fromJson(String data) {
        try {
            return data == null ? null : OBJECT_MAPPER.readValue(data, new TypeReference<>() {});
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
    }

    private static PGobject toJson(Map<String, String> headers) {
        if (headers == null)
            return null;
        try {
            final PGobject holder = new PGobject();
            holder.setType("jsonb");
            holder.setValue(OBJECT_MAPPER.writeValueAsString(headers));
            return holder;
        } catch (JacksonException | SQLException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Delete processed records older than defined point in time
     *
     * @param deleteOlderThan the point in time until the processed entities shall be kept
     * @return amount of deleted rows
     */
    @Transactional
    public int deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(Instant deleteOlderThan) {
        return jdbcTemplate.update(
                "DELETE FROM outbox_kafka WHERE processed IS NOT NULL AND processed < ?",
                Timestamp.from(deleteOlderThan)
        );
    }

}
