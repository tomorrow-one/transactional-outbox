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
package one.tomorrow.transactionaloutbox.tracing;

import lombok.Data;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;

import java.util.Map;

public interface TracingService {

    String INTERNAL_PREFIX = "_internal_:";

    /**
     * Extracts the tracing headers from the current context and returns them as a map.
     * If tracing is not active, an empty map is returned.
     * <p>
     *  This is meant to be used when creating an outbox record, to store the tracing headers with the record.
     * </p>
     */
    Map<String, String> tracingHeadersForOutboxRecord();

    /**
     * Extracts the tracing headers (as created via {@link #tracingHeadersForOutboxRecord()}) from the outbox record
     * to create a span for the time spent in the outbox.<br/>
     * A new span is started for the processing and publishing to Kafka, and headers to publish to Kafka are returned.
     * The span must be completed once the message is published to Kafka.
     */
    TraceOutboxRecordProcessingResult traceOutboxRecordProcessing(OutboxRecord outboxRecord);

    @Data
    abstract class TraceOutboxRecordProcessingResult {

        private final Map<String, String> headers;

        /** Must be invoked once the outbox record was successfully sent to Kafka */
        public abstract void publishCompleted();
        /** Must be invoked if the outbox record could not be sent to Kafka */
        public abstract void publishFailed(Throwable t);

    }

    class HeadersOnlyTraceOutboxRecordProcessingResult extends TraceOutboxRecordProcessingResult {
        public HeadersOnlyTraceOutboxRecordProcessingResult(Map<String, String> headers) {
            super(headers);
        }

        @Override
        public void publishCompleted() {
            // no-op
        }

        @Override
        public void publishFailed(Throwable t) {
            // no-op
        }
    }

}
