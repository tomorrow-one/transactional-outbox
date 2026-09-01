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

import one.tomorrow.transactionaloutbox.model.OutboxRecord;

import java.util.Collections;
import java.util.Map;

/**
 * A no-op implementation of the {@link TracingService} interface. This implementation
 * will be used as a fallback when OpenTelemetry is not available on the classpath.
 * The OpenTelemetryTracingService should be preferred when quarkus-opentelemetry is available.
 */
public class NoopTracingService implements TracingService {

    @Override
    public Map<String, String> tracingHeadersForOutboxRecord() {
        return Collections.emptyMap();
    }

    @Override
    public TraceOutboxRecordProcessingResult traceOutboxRecordProcessing(OutboxRecord outboxRecord) {
        return new HeadersOnlyTraceOutboxRecordProcessingResult(outboxRecord.getHeaders());
    }
}
