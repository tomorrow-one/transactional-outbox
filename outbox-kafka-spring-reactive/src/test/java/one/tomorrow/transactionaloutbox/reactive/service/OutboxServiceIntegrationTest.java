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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

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

}
