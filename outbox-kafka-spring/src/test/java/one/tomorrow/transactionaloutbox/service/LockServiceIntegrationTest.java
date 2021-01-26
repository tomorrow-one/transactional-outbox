package one.tomorrow.transactionaloutbox.service;

import one.tomorrow.transactionaloutbox.IntegrationTestConfig;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.repository.LockRepository;
import org.flywaydb.test.FlywayTestExecutionListener;
import org.flywaydb.test.annotation.FlywayTest;
import org.hibernate.SessionFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {
        OutboxLock.class,
        LockRepository.class,
        IntegrationTestConfig.class
})
@TestExecutionListeners({
        DependencyInjectionTestExecutionListener.class,
        FlywayTestExecutionListener.class
})
@FlywayTest
@SuppressWarnings("unused")
public class LockServiceIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(LockServiceIntegrationTest.class);

    @Autowired
    private SessionFactory sessionFactory;
    @Autowired
    private LockRepository lockRepository;
    @Autowired
    private ApplicationContext applicationContext;

    private static ExecutorService executorService;

    @BeforeClass
    public static void beforeClass() {
        executorService = Executors.newCachedThreadPool();
    }

    @AfterClass
    public static void afterClass() {
        executorService.shutdown();
    }

    @Test
    public void should_RunWithLock_PreventLockStealing() throws ExecutionException, InterruptedException, TimeoutException {
        // given
        String ownerId1 = "owner-1";
        String ownerId2 = "owner-2";
        LockService lockService = postProcessBeanForTransactionCapabilities(new LockService(lockRepository, Duration.ZERO));

        boolean locked = lockService.acquireOrRefreshLock(ownerId1);
        assumeTrue(locked);

        CyclicBarrier barrier1 = new CyclicBarrier(2);
        CyclicBarrier barrier2 = new CyclicBarrier(2);
        CyclicBarrier barrier3 = new CyclicBarrier(2);

        // when
        Future<Boolean> runWithLockResult = executorService.submit(() -> {
            await(barrier1);
            return lockService.runWithLock(ownerId1, () -> {
                await(barrier2);
                await(barrier3); // exit runWithLock not before owner2 has tried to "acquireOrRefreshLock"
            });
        });
        Future<Boolean> lockStealingAttemptResult = executorService.submit(() -> {
            await(barrier1);
            await(barrier2); // start acquireOrRefreshLock not before owner1 is inside "runWithLock"
            boolean result = lockService.acquireOrRefreshLock(ownerId2);
            await(barrier3);
            return result;
        });

        // then
        assertTrue(runWithLockResult.get(5, SECONDS));
        assertFalse(lockStealingAttemptResult.get(5, SECONDS));
    }

    /** Awaits the given barrier, turning checked exceptions into unchecked, for easier usage in lambdas. */
    private void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T postProcessBeanForTransactionCapabilities(T bean) {
        return (T)applicationContext.getAutowireCapableBeanFactory().applyBeanPostProcessorsAfterInitialization(bean, null);
    }

}
