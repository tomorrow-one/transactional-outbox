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

import one.tomorrow.transactionaloutbox.reactive.AbstractIntegrationTest;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.time.temporal.ChronoUnit.MILLIS;
import static one.tomorrow.transactionaloutbox.reactive.TestUtils.newRecord;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.beans.SamePropertyValuesAs.samePropertyValuesAs;

@FlywayTest
@SuppressWarnings("ConstantConditions")
class OutboxRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxRepository testee;

    @Test
    void should_UpdateRecord() {
        // given
        OutboxRecord record = testee.save(
                newRecord("topic1", "key1", "value1", Map.of("h1", "v1"))
        ).block();
        assertThat(record.getProcessed(), is(nullValue()));

        // when
        OutboxRecord update = record.toBuilder().processed(Instant.now()).build();
        testee.save(update).block();

        // then
        OutboxRecord foundUpdate = testee.findById(record.getId()).block();
        assertThat(foundUpdate.getProcessed(), is(notNullValue()));
    }

    @Test
    void should_FindUnprocessedRecords() {
        // given
        testee.save(
                newRecord(Instant.now(),"topic1", "key1", "value1", Map.of("h1", "v1"))
        ).block();

        OutboxRecord record2 = testee.save(
                newRecord("topic2", "key2", "value2", Map.of("h2", "v2"))
        ).block();

        // when
        List<OutboxRecord> result = testee.getUnprocessedRecords(100).collectList().block();

        // then
        assertThat(result.size(), is(1));
        OutboxRecord foundRecord = result.get(0);
        // ignore created, because for the found record it's truncated to micros
        // ignore headers, because Json doesn't implement equals
        assertThat(foundRecord, samePropertyValuesAs(record2, "created", "headers"));
        assertThat(foundRecord.getCreated().truncatedTo(MILLIS), is(equalTo(record2.getCreated().truncatedTo(MILLIS))));
        assertThat(foundRecord.getHeadersAsMap(), is(equalTo(record2.getHeadersAsMap())));
    }

    @Test
    void shouldDeleteOlderProcessedRecords() {
        // given
        OutboxRecord shouldBeKeptAsNotProcessed = testee.save(
                        newRecord(null, "topic1", "key1", "value1", Collections.emptyMap())
                )
                .block();

        OutboxRecord shouldBeKeptAsNotInDeletionPeriod = testee.save(
                        newRecord(Instant.now().minus(Duration.ofDays(1)), "topic1", "key1", "value3", Collections.emptyMap())
                )
                .block();

        OutboxRecord shouldBeDeleted1 = testee.save(newRecord(Instant.now().minus(Duration.ofDays(16)), "topic1", "key1", "value1", Collections.emptyMap()))
                .block();

        OutboxRecord shouldBeDeleted2 = testee.save(newRecord(Instant.now().minus(Duration.ofDays(18)), "topic1", "key1", "value2", Collections.emptyMap()))
                .block();

        // when
        Long deletedRows = testee.deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(Instant.now().minus(Duration.ofDays(15))).block();

        // then
        assertThat(deletedRows, is(2L));
        assertThat(testee.existsById(shouldBeKeptAsNotProcessed.getId()).block(), is(true));
        assertThat(testee.existsById(shouldBeKeptAsNotInDeletionPeriod.getId()).block(), is(true));
        assertThat(testee.existsById(shouldBeDeleted1.getId()).block(), is(false));
        assertThat(testee.existsById(shouldBeDeleted2.getId()).block(), is(false));
    }

}
