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
package one.tomorrow.transactionaloutbox.health;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.service.OutboxProcessor;
import org.eclipse.microprofile.health.*;

import java.time.Duration;
import java.time.Instant;

/**
 * Health check for the Transactional Outbox processor.
 *
 * This health check monitors the status of the outbox processor and provides
 * detailed information about its operational state, including whether it's
 * active, when it last attempted to acquire the lock, and the current
 * number of pending messages in the outbox.
 */
@Readiness
@ApplicationScoped
public class OutboxProcessorHealthCheck implements HealthCheck {

    private static final String HEALTH_CHECK_NAME = "transactional-outbox-processor";

    private final OutboxProcessor outboxProcessor;
    private final OutboxRepository outboxRepository;
    private final TransactionalOutboxConfig config;
    private final Duration staleThreshold;

    @Inject
    public OutboxProcessorHealthCheck(
            OutboxProcessor outboxProcessor,
            OutboxRepository outboxRepository,
            TransactionalOutboxConfig config
    ) {
        this.outboxProcessor = outboxProcessor;
        this.outboxRepository = outboxRepository;
        this.config = config;
        staleThreshold = config.lockTimeout().multipliedBy(2);
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder responseBuilder = HealthCheckResponse.named(HEALTH_CHECK_NAME);

        try {
            // Basic processor information
            addBasicInformation(responseBuilder);

            HealthCheckResponse response = null;

            // If processor is disabled, it's considered healthy
            response = checkEnabledStatus(response, responseBuilder);
            if (response != null)
                return response;

            // If processor is closed, it's unhealthy
            response = checkClosedStatus(response, responseBuilder);
            if (response != null)
                return response;

            // Check lock acquisition status
            response = checkLockAcquisitionStatus(responseBuilder, response);
            if (response != null)
                return response;

            // Get oldest unprocessed message age
            response = checkOldestUnprocessedMessage(responseBuilder, response);
            if (response != null)
                return response;

            // Overall status assessment
            if (outboxProcessor.isActive()) {
                responseBuilder.withData("status", "active");
                return responseBuilder.up().build();
            } else {
                responseBuilder.withData("status", "inactive");
                responseBuilder.withData("reason", "Processor is not currently holding the lock");
                // Being inactive is not necessarily unhealthy in a multi-instance setup
                // Only one instance should be active at a time
                return responseBuilder.up().build();
            }

        } catch (Exception e) {
            Log.errorv(e, "Exception in OutboxProcessorHealthCheck");
            return responseBuilder
                .withData("status", "error")
                .withData("error", e.getMessage())
                .down()
                .build();
        }
    }

    private HealthCheckResponse checkOldestUnprocessedMessage(
            HealthCheckResponseBuilder responseBuilder,
            HealthCheckResponse response
    ) {
        try {
            var unprocessedRecords = outboxRepository.getUnprocessedRecords(1);
            if (!unprocessedRecords.isEmpty()) {
                OutboxRecord oldestRecord = unprocessedRecords.get(0);
                Duration ageOfOldestMessage = Duration.between(oldestRecord.getCreated(), Instant.now());
                responseBuilder.withData("oldestUnprocessedMessageAge", ageOfOldestMessage.toString());
                responseBuilder.withData("oldestUnprocessedMessageCreated", oldestRecord.getCreated().toString());

                // If processor is active and oldest message is older than 2x processing interval,
                // this indicates the processor is not working properly
                Duration processingThreshold = config.processingInterval().multipliedBy(2);
                if (outboxProcessor.isActive() && ageOfOldestMessage.compareTo(processingThreshold) > 0) {
                    response = responseBuilder
                        .withData("status", "processing-stalled")
                        .withData("reason", String.format("Active processor has unprocessed message older than %s (actual age: %s)",
                                processingThreshold, ageOfOldestMessage))
                        .down()
                        .build();
                }
            } else {
                responseBuilder.withData("oldestUnprocessedMessageAge", "none");
            }
        } catch (Exception e) {
            Log.infov("Failed to get oldestUnprocessedMessageAge: {0}", e.getMessage());
        }
        return response;
    }

    private HealthCheckResponse checkLockAcquisitionStatus(HealthCheckResponseBuilder responseBuilder, HealthCheckResponse response) {
        Instant lastLockAttempt = outboxProcessor.getLastLockAcquisitionAttempt();
        if (lastLockAttempt != null) {
            responseBuilder.withData("lastLockAttempt", lastLockAttempt.toString());

            Duration timeSinceLastAttempt = Duration.between(lastLockAttempt, Instant.now());
            responseBuilder.withData("timeSinceLastLockAttempt", timeSinceLastAttempt.toString());

            // Check if the processor seems stale (hasn't attempted lock acquisition recently)
            if (timeSinceLastAttempt.compareTo(staleThreshold) > 0) {
                response = responseBuilder
                    .withData("status", "stale")
                    .withData("reason", String.format("No lock acquisition attempt for %s", timeSinceLastAttempt))
                    .down()
                    .build();
            }
        } else {
            responseBuilder.withData("lastLockAttempt", "never");
        }
        return response;
    }

    private HealthCheckResponse checkClosedStatus(HealthCheckResponse response, HealthCheckResponseBuilder responseBuilder) {
        if (outboxProcessor.isClosed()) {
            response = responseBuilder
                .withData("status", "closed")
                .withData("reason", "Processor has been shut down")
                .down()
                .build();
        }
        return response;
    }

    private HealthCheckResponse checkEnabledStatus(HealthCheckResponse response, HealthCheckResponseBuilder responseBuilder) {
        if (!outboxProcessor.isEnabled()) {
            response = responseBuilder
                .withData("status", "disabled")
                .up()
                .build();
        }
        return response;
    }

    private void addBasicInformation(HealthCheckResponseBuilder responseBuilder) {
        responseBuilder.withData("enabled", outboxProcessor.isEnabled());
        responseBuilder.withData("lockOwnerId", outboxProcessor.getLockOwnerId());
        responseBuilder.withData("active", outboxProcessor.isActive());
        responseBuilder.withData("closed", outboxProcessor.isClosed());
    }
}
