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
package one.tomorrow.transactionaloutbox.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.TransactionRequiredException;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionalException;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@SuppressWarnings({"unused", "ConstantConditions"})
class OutboxServiceIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxServiceIntegrationTest.class);

    @Inject
    OutboxService testee;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    @AfterEach
    @Transactional
    void cleanUp() {
        entityManager
                .createQuery("DELETE FROM OutboxRecord")
                .executeUpdate();
    }

    @Test
    void should_failOnMissingTransaction() {
        byte[] value = "foo".getBytes();
        TransactionalException thrown = assertThrows(
                TransactionalException.class,
                () -> testee.saveForPublishing("topic", "key", value)
        );
        assertInstanceOf(TransactionRequiredException.class, thrown.getCause());
    }

    @Test
    @TestTransaction
    void should_save_withExistingTransaction() {
        // given
        String message = "foo";

        // when
        OutboxRecord result = testee.saveForPublishing("topic", "key", message.getBytes());

        // then
        assertThat(result.getId(), is(notNullValue()));

        OutboxRecord foundRecord = entityManager.find(OutboxRecord.class, result.getId());
        assertThat(foundRecord, is(notNullValue()));
    }

    @Test
    @TestTransaction
    void should_save_withAdditionalHeader() {
        // given
        String message = "foo";
        Map<String, String> additionalHeader = Map.of("key", "value");

        // when
        OutboxRecord result = testee.saveForPublishing("topic", "key", message.getBytes(), additionalHeader);

        // then
        assertThat(result.getId(), is(notNullValue()));

        OutboxRecord foundRecord = entityManager.find(OutboxRecord.class, result.getId());
        assertThat(foundRecord, is(notNullValue()));
        Map.Entry<String, String> entry = additionalHeader.entrySet().iterator().next();
        assertThat(foundRecord.getHeaders(), hasEntry(entry.getKey(), entry.getValue()));
    }

}
