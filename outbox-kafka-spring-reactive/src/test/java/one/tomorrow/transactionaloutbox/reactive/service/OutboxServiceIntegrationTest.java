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
package one.tomorrow.transactionaloutbox.reactive.service;

import one.tomorrow.transactionaloutbox.reactive.AbstractIntegrationTest;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.reactive.test.Sample.SomethingHappened;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.hamcrest.collection.IsMapContaining.hasKey;

@FlywayTest
@SuppressWarnings({"unused", "ConstantConditions"})
class OutboxServiceIntegrationTest extends AbstractIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxServiceIntegrationTest.class);

    @Autowired
    private OutboxService testee;
    @Autowired
    private OutboxRepository repository;
    @Autowired
    private TransactionalOperator rxtx;

    @AfterEach
    public void cleanUp() {
        repository.deleteAll().block();
    }

    @Test
    void should_failOnMissingTransaction() {
        // given
        SomethingHappened message = SomethingHappened.newBuilder().setId(1).setName("foo").build();

        // when
        Mono<OutboxRecord> result = testee.saveForPublishing("topic", "key", message);

        // then
        StepVerifier.create(result)
                .expectError(IllegalTransactionStateException.class)
                .verify();
    }

    @Test
    void should_save_withExistingTransaction() {
        // given
        SomethingHappened message = SomethingHappened.newBuilder().setId(1).setName("foo").build();

        // when
        Mono<OutboxRecord> result = testee.saveForPublishing("topic", "key", message)
                .as(rxtx::transactional);

        // then
        OutboxRecord savedRecord = result.block();
        assertThat(savedRecord.getId(), is(notNullValue()));

        OutboxRecord foundRecord = repository.findById(savedRecord.getId()).block();
        assertThat(foundRecord, is(notNullValue()));
    }

    @Test
    void should_save_withAdditionalHeader() {
        // given
        SomethingHappened message = SomethingHappened.newBuilder().setId(1).setName("foo").build();
        OutboxService.Header additionalHeader = new OutboxService.Header("key", "value");

        // when
        Mono<OutboxRecord> result = testee.saveForPublishing("topic", "key", message, additionalHeader)
                .as(rxtx::transactional);

        // then
        OutboxRecord savedRecord = result.block();
        assertThat(savedRecord.getId(), is(notNullValue()));

        OutboxRecord foundRecord = repository.findById(savedRecord.getId()).block();
        assertThat(foundRecord, is(notNullValue()));
        assertThat(foundRecord.getHeadersAsMap(), hasEntry(additionalHeader.getKey(), additionalHeader.getValue()));
    }

}
