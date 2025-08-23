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

import java.time.Duration;

/**
 * Utility class for tests that provides a TransactionalOutboxConfig with default values.
 */
public class TestTransactionalOutboxConfig {

    public static TransactionalOutboxConfig createDefault() {
        return createConfig(Duration.ofMillis(200), Duration.ofSeconds(5), "testLockOwnerId", "testEventSource");
    }

    public static TransactionalOutboxConfig createConfig(
            Duration processingInterval,
            Duration lockTimeout,
            String lockOwnerId,
            String eventSource) {

        return new TransactionalOutboxConfig() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Duration processingInterval() {
                return processingInterval;
            }

            @Override
            public Duration lockTimeout() {
                return lockTimeout;
            }

            @Override
            public String lockOwnerId() {
                return lockOwnerId;
            }

            @Override
            public String eventSource() {
                return eventSource;
            }
        };
    }
}
