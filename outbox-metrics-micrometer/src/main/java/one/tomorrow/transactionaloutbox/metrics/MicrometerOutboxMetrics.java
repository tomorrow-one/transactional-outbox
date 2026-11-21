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

import io.micrometer.core.instrument.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One instance per processor, with tags uniquely identifying that processor in the registry. */
public final class MicrometerOutboxMetrics implements OutboxMetrics {
    private final MeterRegistry registry;
    private final Tags tags;
    private final Duration interval;
    private final Timer success;
    private final Timer failure;
    private final Counter failures;
    private final AtomicBoolean monitoring = new AtomicBoolean();

    public MicrometerOutboxMetrics(MeterRegistry registry, Tags tags, Duration interval) {
        this.registry = Objects.requireNonNull(registry);
        this.tags = Objects.requireNonNull(tags);
        this.interval = Objects.requireNonNull(interval);
        if (interval.toMillis() < 1) throw new IllegalArgumentException("Sampling interval must be at least 1 ms");
        success = Timer.builder("outbox.publish.duration").tags(tags).tag("outcome", "success").register(registry);
        failure = Timer.builder("outbox.publish.duration").tags(tags).tag("outcome", "failure").register(registry);
        failures = Counter.builder("outbox.publish.failures").tags(tags).register(registry);
    }

    @Override
    public Attempt startPublish() {
        Timer.Sample sample = Timer.start(registry);
        AtomicBoolean completed = new AtomicBoolean();
        return succeeded -> {
            if (completed.compareAndSet(false, true)) {
                sample.stop(succeeded ? success : failure);
                if (!succeeded) failures.increment();
            }
        };
    }

    @Override
    public Registration monitor(Supplier<CompletionStage<Pending>> pending, BooleanSupplier active) {
        if (!monitoring.compareAndSet(false, true))
            throw new IllegalStateException("Use a separate metrics instance for each processor");
        State state = new State(interval.multipliedBy(2));
        List<Meter> meters = List.of(
                Gauge.builder("outbox.pending", state, State::count)
                        .tags(tags).register(registry),
                Gauge.builder("outbox.oldest.pending.age", state, State::age)
                        .baseUnit("seconds").tags(tags).register(registry),
                Gauge.builder("outbox.processor.active", state, s -> {
                    try { return !s.closed && active.getAsBoolean() ? 1 : 0; }
                    catch (RuntimeException e) { return Double.NaN; }
                }).tags(tags).register(registry));
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "outbox-metrics");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            if (state.closed || !state.inFlight.compareAndSet(false, true)) return;
            try {
                pending.get().whenComplete((snapshot, error) -> {
                    if (!state.closed) {
                        state.sampledAt = System.nanoTime();
                        state.pending = error == null ? snapshot : null;
                    }
                    state.inFlight.set(false);
                });
            } catch (RuntimeException e) {
                state.pending = null;
                state.inFlight.set(false);
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        AtomicBoolean stopped = new AtomicBoolean();
        return () -> {
            if (!stopped.compareAndSet(false, true)) return;
            state.closed = true;
            executor.shutdownNow();
            meters.forEach(registry::remove);
        };
    }

    private static final class State {
        private volatile Pending pending;
        private volatile long sampledAt;
        private final long maxAgeNanos;
        State(Duration maxAge) { maxAgeNanos = maxAge.toNanos(); }
        Pending snapshot() {
            return System.nanoTime() - sampledAt > maxAgeNanos ? null : pending;
        }
        double count() {
            Pending snapshot = snapshot();
            return snapshot == null ? Double.NaN : snapshot.count();
        }
        private volatile boolean closed;
        private final AtomicBoolean inFlight = new AtomicBoolean();
        double age() {
            Pending snapshot = snapshot();
            if (snapshot == null) return Double.NaN;
            if (snapshot.count() == 0) return 0;
            if (snapshot.oldestCreated() == null) return Double.NaN;
            return Math.max(0, Duration.between(snapshot.oldestCreated(), Instant.now()).toMillis() / 1000.0);
        }
    }
}
