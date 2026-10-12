/**
 * Copyright 2026 Tomorrow GmbH @ https://tomorrow.one
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

import io.r2dbc.spi.ConnectionFactories;
import one.tomorrow.transactionaloutbox.commons.DatabaseDialect;
import one.tomorrow.transactionaloutbox.commons.DatabaseTestSupport;
import one.tomorrow.transactionaloutbox.reactive.config.OutboxR2dbcConversions;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseCompatibilityIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static DatabaseTestSupport databases;

    @BeforeAll
    static void startDatabases() {
        databases = new DatabaseTestSupport();
    }

    @AfterAll
    static void stopDatabases() {
        if (databases != null) databases.close();
    }

    private Fixture fixture(DatabaseDialect database) {
        databases.clean(database);
        var factory = ConnectionFactories.get(databases.r2dbcUrl(database));
        var dialect = DialectResolver.getDialect(factory);
        var conversions = OutboxR2dbcConversions.create(factory);
        var context = new R2dbcMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        var converter = new MappingR2dbcConverter(context, conversions);
        var db = DatabaseClient.create(factory);
        var template = new R2dbcEntityTemplate(db, dialect, converter);
        var repository = new R2dbcRepositoryFactory(template).getRepository(OutboxRepository.class);
        var tx = TransactionalOperator.create(new R2dbcTransactionManager(factory));
        return new Fixture(db, repository, new OutboxLockRepository(db, tx), tx);
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void roundTripsHeadersBinaryNullsAndUtcTimestamps(DatabaseDialect database) {
        Fixture f = fixture(database);
        byte[] payload = new byte[100_000];
        payload[0] = (byte) 255;
        Map<String, String> headers = Map.of("한글", "quoted \"value\" 🐬");
        Instant created = Instant.parse("2026-01-02T03:04:05.123456Z");
        var first = f.records.save(record(created, payload, headers)).block(WAIT);
        var second = f.records.save(record(created, new byte[0], null).toBuilder().key(null).build()).block(WAIT);
        assertTrue(first.getId() < second.getId());
        var rows = f.records.getUnprocessedRecords(1).collectList().block(WAIT);
        assertEquals(1, rows.size());
        var found = rows.get(0);
        assertEquals(first.getId(), found.getId());
        assertEquals("key", found.getKey());
        assertEquals(created, found.getCreated());
        assertEquals(headers, found.getHeadersAsMap());
        assertArrayEquals(payload, found.getValue());
        var nulls = f.records.findById(second.getId()).block(WAIT);
        assertNull(nulls.getKey());
        assertTrue(nulls.getHeadersAsMap().isEmpty());
        Instant processed = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        f.records.save(first.toBuilder().processed(processed).build()).block(WAIT);
        assertEquals(processed, f.records.findById(first.getId()).block(WAIT).getProcessed());
        assertEquals(1L, f.records.deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(Instant.now()).block(WAIT));
        assertEquals(second.getId(), f.records.getUnprocessedRecords(10).blockFirst(WAIT).getId());
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void rollsBackWithBusinessTransaction(DatabaseDialect database) {
        Fixture f = fixture(database);
        Mono<Void> work = f.db.sql("insert into business_record (id) values (1)").fetch().rowsUpdated()
                .then(f.records.save(record(Instant.now(), new byte[]{1}, Map.of())))
                .then(Mono.error(new IllegalStateException("business operation failed")));
        assertThrows(IllegalStateException.class, () -> work.as(f.tx::transactional).block(WAIT));
        assertEquals(0, new JdbcTemplate(databases.dataSource(database))
                .queryForObject("select count(*) from business_record", Integer.class));
        assertEquals(0L, f.records.count().block(WAIT));
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void acquiresRefreshesAndTransfersExpiredLock(DatabaseDialect database) {
        Fixture f = fixture(database);
        Duration timeout = Duration.ofSeconds(30);
        assertTrue(f.locks.acquireOrRefreshLock("Owner", timeout, false).block(WAIT));
        assertTrue(f.locks.acquireOrRefreshLock("Owner", timeout, true).block(WAIT));
        assertFalse(f.locks.acquireOrRefreshLock("owner", timeout, false).block(WAIT));
        f.locks.releaseLock("owner").block(WAIT);
        assertFalse(f.locks.acquireOrRefreshLock("other", timeout, false).block(WAIT));
        expire(database);
        assertTrue(f.locks.acquireOrRefreshLock("other", timeout, false).block(WAIT));
        assertFalse(f.locks.acquireOrRefreshLock("Owner", timeout, true).block(WAIT));
        f.locks.releaseLock("other").block(WAIT);
        assertTrue(f.locks.acquireOrRefreshLock("Owner", timeout, false).block(WAIT));
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void handlesNowaitContentionAndRecovers(DatabaseDialect database) throws Exception {
        Fixture f = fixture(database);
        assertTrue(f.locks.acquireOrRefreshLock("owner", Duration.ofSeconds(30), false).block(WAIT));
        try (Connection connection = databases.dataSource(database).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement();
                var ignored = statement.executeQuery("select * from outbox_kafka_lock for update")) {
                assertFalse(f.locks.acquireOrRefreshLock("owner", Duration.ofSeconds(30), true).block(Duration.ofSeconds(5)));
            } finally {
                connection.rollback();
            }
        }
        assertTrue(f.locks.acquireOrRefreshLock("owner", Duration.ofSeconds(30), true).block(WAIT));
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void permitsOnlyOneConcurrentOwner(DatabaseDialect database) {
        Fixture f = fixture(database);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) expire(database);
            int round = attempt;
            Long winners = Flux.range(0, 6).flatMap(i -> f.locks.acquireOrRefreshLock(
                    "owner-" + round + "-" + i, Duration.ofSeconds(30), false), 6)
                    .filter(Boolean::booleanValue).count().block(WAIT);
            assertEquals(1L, winners);
        }
    }

    @ParameterizedTest
    @EnumSource(DatabaseDialect.class)
    void preventsStealingWhileProcessing(DatabaseDialect database) {
        Fixture f = fixture(database);
        assertTrue(f.locks.acquireOrRefreshLock("owner", Duration.ofSeconds(30), false).block(WAIT));
        expire(database);
        Sinks.One<Void> lockHeld = Sinks.one();
        Sinks.One<Void> contenderFinished = Sinks.one();
        Mono<Void> processing = f.locks.preventLockStealing("owner")
                .doOnNext(held -> {
                    assertTrue(held);
                    lockHeld.tryEmitEmpty();
                })
                .then(contenderFinished.asMono())
                .as(f.tx::transactional);
        // Sibling subscriptions keep transaction contexts separate. Never block a database
        // event-loop thread while waiting for another connection to finish its query.
        Mono<Void> contender = lockHeld.asMono()
                .then(Mono.defer(() -> f.locks.acquireOrRefreshLock("other", Duration.ofSeconds(30), false)))
                .doOnNext(acquired -> assertFalse(acquired))
                .then()
                .doOnSuccess(ignored -> contenderFinished.tryEmitEmpty());
        Mono.when(processing, contender).block(WAIT);
        assertTrue(f.locks.acquireOrRefreshLock("other", Duration.ofSeconds(30), false).block(WAIT));
    }

    private void expire(DatabaseDialect database) {
        new JdbcTemplate(databases.dataSource(database)).update(
                "update outbox_kafka_lock set valid_until = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
    }

    private OutboxRecord record(Instant created, byte[] value, Map<String, String> headers) {
        return OutboxRecord.builder().created(created).topic("topic").key("key").value(value)
                .headers(OutboxRecord.toJson(headers)).build();
    }

    private record Fixture(DatabaseClient db, OutboxRepository records, OutboxLockRepository locks,
                            TransactionalOperator tx) {}
}
