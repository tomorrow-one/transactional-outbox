/**
 * Copyright 2025 Tomorrow GmbH @ https://tomorrow.one
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
package one.tomorrow.transactionaloutbox.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "one.tomorrow.transactional-outbox", namingStrategy = ConfigMapping.NamingStrategy.KEBAB_CASE)
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface TransactionalOutboxConfig {

    /**
     * Whether the outbox processor is enabled, default true.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The interval between outbox processing cycles.
     * Should be significantly smaller than the configured lock timeout.
     * Recommended value: 200ms (PT0.2S)
     */
    @WithDefault("PT0.2S")
    Duration processingInterval();

    /**
     * The time after that a lock is considered to be lost/stale, maybe because
     * the lock owner crashed or was restarted. After that time another instance
     * is allowed to grab and acquire the lock in order to process the outbox.
     *
     * The value should be significantly higher than the configured processing-interval,
     * it should be higher than gc pauses or system stalls, e.g. between 2 and 10 seconds (e.g. PT5S).
     */
    @WithDefault("PT5S")
    Duration lockTimeout();

    /**
     * A unique identifier for the instance attempting to acquire the lock.
     * Example: the hostname of the instance.
     */
    String lockOwnerId();

    /**
     * A string identifying the source of the events, used as the value for the `x-source` header.
     * Example: the service name or a unique identifier for the producer.
     */
    String eventSource();
}
