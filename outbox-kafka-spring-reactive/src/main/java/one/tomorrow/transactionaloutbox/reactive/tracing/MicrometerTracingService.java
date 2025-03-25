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
package one.tomorrow.transactionaloutbox.reactive.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.AllArgsConstructor;
import one.tomorrow.transactionaloutbox.commons.spring.ConditionalOnClass;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ConditionalOnClass(Tracer.class)
@Service
@Primary // if this is not good enough, NoopTracingService could use our own implementation of @ConditionalOnMissingBean
@AllArgsConstructor
public class MicrometerTracingService implements TracingService {

    static final String TO_PREFIX = "To_";

    private final Tracer tracer;
    private final Propagator propagator;

    @Override
    public Map<String, String> tracingHeadersForOutboxRecord() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        propagator.inject(
                context,
                result,
                (map, k, v) -> map.put(INTERNAL_PREFIX + k, v)
        );
        return result;
    }

    @Override
    public TraceOutboxRecordProcessingResult traceOutboxRecordProcessing(OutboxRecord outboxRecord) {
        Map<String, String> headers = outboxRecord.getHeadersAsMap();
        Set<Entry<String, String>> headerEntries = headers.entrySet();
        boolean containsTraceInfo = headerEntries.stream().anyMatch(e -> e.getKey().startsWith(INTERNAL_PREFIX));
        if (!containsTraceInfo) {
            return new HeadersOnlyTraceOutboxRecordProcessingResult(headers);
        }

        // This creates a new span with the same trace ID as the parent span
        Span outboxSpan = propagator.extract(headers, (map, k) -> map.get(INTERNAL_PREFIX + k))
                .name("transactional-outbox")
                .startTimestamp(outboxRecord.getCreated().toEpochMilli(), TimeUnit.MILLISECONDS)
                .start();
        outboxSpan.end();

        Map<String, String> newHeaders = headerEntries.stream()
                .filter(entry -> !entry.getKey().startsWith(INTERNAL_PREFIX))
                .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

        // the span for publishing to Kafka - this span will be propagated via Kafka, and could be
        // referenced by consumers via "follows_from" relationship or set as parent span
        Span processingSpan = tracer.spanBuilder()
                .setParent(outboxSpan.context())
                .name(TO_PREFIX + outboxRecord.getTopic()) // provides better readability in the UI
                .kind(Span.Kind.PRODUCER)
                .start();

        propagator.inject(processingSpan.context(), newHeaders, Map::put);

        return new TraceOutboxRecordProcessingResult(newHeaders) {
            @Override
            public void publishCompleted() {
                processingSpan.end();
            }
            @Override
            public void publishFailed(Throwable t) {
                processingSpan.error(t);
            }
        };
    }

}
