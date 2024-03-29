/**
 * Copyright 2023 Tomorrow GmbH @ https://tomorrow.one
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
import one.tomorrow.transactionaloutbox.reactive.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxLock;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxRepository;
import org.flywaydb.test.annotation.FlywayTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsMapContaining.hasEntry;

@ContextConfiguration(classes = {
        OutboxLockRepository.class,
        OutboxLock.class,
        OutboxLockService.class,
        OutboxService.class,
        IntegrationTestConfig.class
})
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
        String message = "foo";

        // when
        Mono<OutboxRecord> result = testee.saveForPublishing("topic", "key", message.getBytes());

        // then
        StepVerifier.create(result)
                .expectError(IllegalTransactionStateException.class)
                .verify();
    }

    @Test
    void should_save_withExistingTransaction() {
        // given
        String message = "foo";

        // when
        Mono<OutboxRecord> result = testee.saveForPublishing("topic", "key", message.getBytes())
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
        String message = "foo";
        Map<String, String> additionalHeader = Map.of("key", "value");

        // when
        Mono<OutboxRecord> result = testee.saveForPublishing("topic", "key", message.getBytes(), additionalHeader)
                .as(rxtx::transactional);

        // then
        OutboxRecord savedRecord = result.block();
        assertThat(savedRecord.getId(), is(notNullValue()));

        OutboxRecord foundRecord = repository.findById(savedRecord.getId()).block();
        assertThat(foundRecord, is(notNullValue()));
        Map.Entry<String, String> entry = additionalHeader.entrySet().iterator().next();
        assertThat(foundRecord.getHeadersAsMap(), hasEntry(entry.getKey(), entry.getValue()));
    }

}
