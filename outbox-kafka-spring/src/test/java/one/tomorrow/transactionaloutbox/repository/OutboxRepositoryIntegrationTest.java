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

import one.tomorrow.transactionaloutbox.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.jdbc.SqlScriptsTestExecutionListener;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static one.tomorrow.transactionaloutbox.TestUtils.newHeaders;
import static one.tomorrow.transactionaloutbox.TestUtils.newRecord;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        OutboxRepository.class,
        OutboxRecord.class,
        IntegrationTestConfig.class})
@TestExecutionListeners({
        DependencyInjectionTestExecutionListener.class,
        TransactionalTestExecutionListener.class,
        SqlScriptsTestExecutionListener.class,
        DirtiesContextTestExecutionListener.class,
        FlywayTestExecutionListener.class})
@FlywayTest
@Transactional
public class OutboxRepositoryIntegrationTest {

    @Autowired
    private OutboxRepository testee;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void should_FindUnprocessedRecords() {
        // given
        OutboxRecord record1 = newRecord(Instant.now(), "topic1", "key1", "value1", newHeaders("h1", "v1"));
        testee.persist(record1);

        OutboxRecord record2 = newRecord("topic2", "key2", "value2", newHeaders("h2", "v2"));
        testee.persist(record2);

        // when
        List<OutboxRecord> result = testee.getUnprocessedRecords(100);

        // then
        assertThat(result.size(), CoreMatchers.is(1));
        OutboxRecord foundRecord = result.get(0);
        assertEquals(record2, foundRecord);
    }

    @Test
    public void should_DeleteProcessedRecordsAfterRetentionTime() {
        // given
        OutboxRecord shouldBeKeptAsNotProcessed = newRecord(null, "topic1", "key1", "value1", Collections.emptyMap());
        testee.persist(shouldBeKeptAsNotProcessed);

        OutboxRecord shouldBeKeptAsNotInDeletionPeriod = newRecord(Instant.now().minus(Duration.ofDays(1)), "topic1", "key1", "value3", Collections.emptyMap());
        testee.persist(shouldBeKeptAsNotInDeletionPeriod);

        OutboxRecord shouldBeDeleted1 = newRecord(Instant.now().minus(Duration.ofDays(16)), "topic1", "key1", "value1", Collections.emptyMap());
        testee.persist(shouldBeDeleted1);

        OutboxRecord shouldBeDeleted2 = newRecord(Instant.now().minus(Duration.ofDays(18)), "topic1", "key1", "value2", Collections.emptyMap());
        testee.persist(shouldBeDeleted2);
        OutboxRecord shouldBeDeleted3 = newRecord(Instant.now().minus(Duration.ofDays(150)), "topic1", "key1", "value2", Collections.emptyMap());
        testee.persist(shouldBeDeleted3);

        // when
        Integer result = testee.deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(Instant.now().minus(Duration.ofDays(15)));

        // then
        assertThat(result, CoreMatchers.is(3));
        assertFalse(outboxRecordExists(shouldBeDeleted1.getId()));
        assertFalse(outboxRecordExists(shouldBeDeleted2.getId()));
        assertFalse(outboxRecordExists(shouldBeDeleted3.getId()));
        assertTrue(outboxRecordExists(shouldBeKeptAsNotInDeletionPeriod.getId()));
        assertTrue(outboxRecordExists(shouldBeKeptAsNotProcessed.getId()));
    }

    private boolean outboxRecordExists(Long id) {
        Long result = jdbcTemplate.query(
                "select count(*) from outbox_kafka where id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                id
        );
        return result != null && result > 0;
    }

}
