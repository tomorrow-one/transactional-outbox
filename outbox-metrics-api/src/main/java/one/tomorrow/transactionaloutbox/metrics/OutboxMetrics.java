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

import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Optional instrumentation. Implementations must not interfere with message delivery. */
public interface OutboxMetrics {
    OutboxMetrics NOOP = new OutboxMetrics() {};
    Attempt NOOP_ATTEMPT = success -> {};
    Registration NOOP_REGISTRATION = () -> {};

    /** Count and oldest creation time must describe all pending rows, not just a processing batch. */
    record Pending(long count, Instant oldestCreated) {}

    /** One Kafka publishing attempt; success means broker acknowledgement, not a database update. */
    interface Attempt {
        void complete(boolean success);
    }

    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    default Attempt startPublish() { return NOOP_ATTEMPT; }

    /** Implementations may sample asynchronously. The default does not invoke either supplier. */
    default Registration monitor(Supplier<CompletionStage<Pending>> pending, BooleanSupplier active) {
        return NOOP_REGISTRATION;
    }

    /** Isolate instrumentation failures from application processing. */
    static OutboxMetrics safe(OutboxMetrics delegate) {
        if (delegate == null || delegate == NOOP) return NOOP;
        return new OutboxMetrics() {
            @Override
            public Attempt startPublish() {
                try {
                    Attempt attempt = delegate.startPublish();
                    return success -> {
                        try { attempt.complete(success); } catch (RuntimeException ignored) { }
                    };
                } catch (RuntimeException ignored) { return NOOP_ATTEMPT; }
            }
            @Override
            public Registration monitor(Supplier<CompletionStage<Pending>> pending, BooleanSupplier active) {
                try {
                    Registration registration = delegate.monitor(pending, active);
                    return () -> {
                        try { registration.close(); } catch (RuntimeException ignored) { }
                    };
                } catch (RuntimeException ignored) { return NOOP_REGISTRATION; }
            }
        };
    }
}
