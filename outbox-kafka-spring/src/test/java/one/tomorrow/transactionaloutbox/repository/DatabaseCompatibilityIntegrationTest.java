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

import one.tomorrow.transactionaloutbox.commons.DatabaseDialect;
import one.tomorrow.transactionaloutbox.commons.DatabaseTestSupport;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseCompatibilityIntegrationTest {
    private static DatabaseTestSupport databases;

    @BeforeAll
    static void startDatabases() {
        databases = new DatabaseTestSupport();
    }

    @AfterAll
    static void stopDatabases() {
        if (databases != null) databases.close();
    }

    private Fixture fixture(DatabaseDialect dialect) {
        databases.clean(dialect);
        DataSource dataSource = databases.dataSource(dialect);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        return new Fixture(dataSource, jdbc, new OutboxRepository(jdbc), new OutboxLockRepository(jdbc, manager),
                new TransactionTemplate(manager));
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void roundTripsPayloadAndHeadersAndCleansProcessedRecords(DatabaseDialect dialect) {
        Fixture f = fixture(dialect);
        byte[] payload = new byte[100_000];
        payload[0] = (byte) 255;
        Map<String, String> headers = Map.of("한글", "value \"quoted\" 🐬");
        OutboxRecord first = record(payload, headers);
        OutboxRecord second = record(new byte[0], null);
        f.records.persist(first);
        f.records.persist(second);
        assertTrue(first.getId() < second.getId());
        var found = f.records.getUnprocessedRecords(1);
        assertEquals(1, found.size());
        assertEquals(first.getId(), found.get(0).getId());
        assertEquals("key", found.get(0).getKey());
        assertArrayEquals(payload, found.get(0).getValue());
        assertEquals(headers, found.get(0).getHeaders());
        assertEquals(first.getCreated(), found.get(0).getCreated());
        assertNull(f.records.getUnprocessedRecords(2).get(1).getHeaders());
        Instant processed = Instant.now().minusSeconds(60);
        f.records.updateProcessed(first.getId(), processed);
        assertEquals(second.getId(), f.records.getUnprocessedRecords(10).get(0).getId());
        assertEquals(1, f.records.deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(Instant.now()));
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void rollsBackWithBusinessTransaction(DatabaseDialect dialect) {
        Fixture f = fixture(dialect);
        assertThrows(IllegalStateException.class, () -> f.tx.executeWithoutResult(status -> {
            f.jdbc.update("insert into business_record (id) values (1)");
            f.records.persist(record(new byte[]{1}, Map.of()));
            throw new IllegalStateException("business operation failed");
        }));
        assertEquals(0, f.jdbc.queryForObject("select count(*) from business_record", Integer.class));
        assertTrue(f.records.getUnprocessedRecords(10).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void acquiresRefreshesAndTransfersExpiredLock(DatabaseDialect dialect) {
        Fixture f = fixture(dialect);
        Duration timeout = Duration.ofSeconds(30);
        assertTrue(f.locks.acquireOrRefreshLock("Owner", timeout));
        assertTrue(f.locks.acquireOrRefreshLock("Owner", timeout));
        assertFalse(f.locks.acquireOrRefreshLock("owner", timeout));
        f.locks.releaseLock("owner");
        assertFalse(f.locks.acquireOrRefreshLock("other", timeout));
        expire(f);
        assertTrue(f.locks.acquireOrRefreshLock("other", timeout));
        assertFalse(f.locks.acquireOrRefreshLock("Owner", timeout));
        f.locks.releaseLock("other");
        assertTrue(f.locks.acquireOrRefreshLock("Owner", timeout));
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void returnsFalseWithoutWaitingForLockedRow(DatabaseDialect dialect) throws Exception {
        Fixture f = fixture(dialect);
        assertTrue(f.locks.acquireOrRefreshLock("owner", Duration.ofSeconds(30)));
        try (Connection connection = f.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement();
                 var ignored = statement.executeQuery("select * from outbox_kafka_lock for update")) {
                assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> assertFalse(f.locks.acquireOrRefreshLock("owner", Duration.ofSeconds(30))));
            } finally {
                connection.rollback();
            }
        }
        assertTrue(f.locks.acquireOrRefreshLock("owner", Duration.ofSeconds(30)));
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void permitsOnlyOneConcurrentOwner(DatabaseDialect dialect) throws Exception {
        Fixture f = fixture(dialect);
        var executor = Executors.newFixedThreadPool(6);
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) expire(f);
                CyclicBarrier start = new CyclicBarrier(6);
                int round = attempt;
                var calls = IntStream.range(0, 6).mapToObj(i -> (Callable<Boolean>) () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return f.locks.acquireOrRefreshLock("owner-" + round + "-" + i, Duration.ofSeconds(30));
                }).toList();
                int winners = 0;
                for (var future : executor.invokeAll(calls, 20, TimeUnit.SECONDS))
                    if (future.get()) winners++;
                assertEquals(1, winners);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void preventsStealingUntilProcessingTransactionCommits(DatabaseDialect dialect) {
        Fixture f = fixture(dialect);
        assertTrue(f.locks.acquireOrRefreshLock("owner", Duration.ofSeconds(30)));
        expire(f);
        f.tx.executeWithoutResult(status -> {
            assertTrue(f.locks.preventLockStealing("owner"));
            assertFalse(f.locks.preventLockStealing("OWNER"));
            // REQUIRES_NEW uses a separate connection while the outer transaction holds its shared lock.
            assertFalse(f.locks.acquireOrRefreshLock("other", Duration.ofSeconds(30)));
        });
        assertTrue(f.locks.acquireOrRefreshLock("other", Duration.ofSeconds(30)));
    }

    private void expire(Fixture f) {
        f.jdbc.update("update outbox_kafka_lock set valid_until = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
    }

    private OutboxRecord record(byte[] payload, Map<String, String> headers) {
        return OutboxRecord.builder().topic("topic").key("key").value(payload).headers(headers).build();
    }

    private record Fixture(DataSource dataSource, JdbcTemplate jdbc, OutboxRepository records,
                           OutboxLockRepository locks, TransactionTemplate tx) {}
}
