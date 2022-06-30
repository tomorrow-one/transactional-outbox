package one.tomorrow.transactionaloutbox.repository;

import one.tomorrow.transactionaloutbox.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.hamcrest.CoreMatchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.jdbc.SqlScriptsTestExecutionListener;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static one.tomorrow.transactionaloutbox.TestUtils.newHeaders;
import static one.tomorrow.transactionaloutbox.TestUtils.newRecord;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {
        LegacyOutboxSessionFactory.class,
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

    @Test
    public void should_FindUnprocessedRecords() {
        // given
        OutboxRecord record1 = newRecord(Instant.now(),"topic1", "key1", "value1", newHeaders("h1", "v1"));
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

}
