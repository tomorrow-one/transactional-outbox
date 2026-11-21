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
package one.tomorrow.transactionaloutbox.metrics;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class MicrometerOutboxMetricsTest {
    @Test
    void recordsEachAttemptOnceAndSeparatesOutcomes() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new MicrometerOutboxMetrics(registry, Tags.of("source", "orders"), Duration.ofSeconds(1));
            var success = metrics.startPublish();
            success.complete(true);
            success.complete(false);
            metrics.startPublish().complete(false);
            assertEquals(1, registry.get("outbox.publish.duration").tag("outcome", "success").timer().count());
            assertEquals(1, registry.get("outbox.publish.duration").tag("outcome", "failure").timer().count());
            assertEquals(1, registry.get("outbox.publish.failures").counter().count());
        } finally { registry.close(); }
    }

    @Test
    void samplesBacklogRecoversFromFailureAndRemovesGaugesOnClose() throws Exception {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new MicrometerOutboxMetrics(registry, Tags.empty(), Duration.ofMillis(100));
            var next = new AtomicReference<>(CompletableFuture.completedFuture(
                    new OutboxMetrics.Pending(3, Instant.now().minusSeconds(60))));
            var registration = metrics.monitor(next::get, () -> true);
            try {
                await(() -> registry.get("outbox.pending").gauge().value() == 3);
                assertTrue(registry.get("outbox.oldest.pending.age").gauge().value() >= 60);
                assertEquals(1, registry.get("outbox.processor.active").gauge().value());
                next.set(CompletableFuture.failedFuture(new IllegalStateException("database unavailable")));
                await(() -> Double.isNaN(registry.get("outbox.pending").gauge().value()));
                next.set(CompletableFuture.completedFuture(new OutboxMetrics.Pending(0, null)));
                await(() -> registry.get("outbox.pending").gauge().value() == 0);
                assertEquals(0, registry.get("outbox.oldest.pending.age").gauge().value());
            } finally { registration.close(); }
            assertNull(registry.find("outbox.pending").gauge());
        } finally { registry.close(); }
    }

    @Test
    void doesNotOverlapSlowQueries() throws Exception {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new MicrometerOutboxMetrics(registry, Tags.empty(), Duration.ofMillis(10));
            var calls = new AtomicInteger();
            var pending = new CompletableFuture<OutboxMetrics.Pending>();
            try (var registration = metrics.monitor(() -> calls.incrementAndGet() == 1
                    ? CompletableFuture.completedFuture(new OutboxMetrics.Pending(2, Instant.now())) : pending,
                    () -> false)) {
                await(() -> calls.get() == 2);
                Thread.sleep(70);
                assertEquals(2, calls.get());
                assertTrue(Double.isNaN(registry.get("outbox.pending").gauge().value()));
            } finally { pending.complete(new OutboxMetrics.Pending(0, null)); }
        } finally { registry.close(); }
    }

    @Test
    void noopDoesNotQueryAndFaultyInstrumentationIsIsolated() {
        OutboxMetrics.NOOP.monitor(() -> { fail("No database queries without metrics"); return null; }, () -> false).close();
        var broken = OutboxMetrics.safe(new OutboxMetrics() {
            public Attempt startPublish() { return success -> { throw new IllegalStateException(); }; }
            public Registration monitor(java.util.function.Supplier<java.util.concurrent.CompletionStage<Pending>> p,
                    BooleanSupplier a) { throw new IllegalStateException(); }
        });
        assertDoesNotThrow(() -> broken.startPublish().complete(true));
        assertDoesNotThrow(() -> broken.monitor(() -> null, () -> false).close());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Condition did not become true");
    }
}
