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

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class OpenTelemetryTracingService implements TracingService {

    static final String TO_PREFIX = "To_";

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public OpenTelemetryTracingService(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = this.openTelemetry.getTracer("one.tomorrow.transactional-outbox");
    }

    // TextMapGetter for extracting from headers
    private static final TextMapGetter<Map<String, String>> HEADER_GETTER = new TextMapGetter<Map<String, String>>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(INTERNAL_PREFIX + key);
        }
    };

    // TextMapSetter for injecting into headers
    private static final TextMapSetter<Map<String, String>> HEADER_SETTER = (carrier, key, value) ->
        carrier.put(INTERNAL_PREFIX + key, value);

    // TextMapSetter for injecting into Kafka headers (without internal prefix)
    private static final TextMapSetter<Map<String, String>> KAFKA_HEADER_SETTER = Map::put;

    @Override
    public Map<String, String> tracingHeadersForOutboxRecord() {
        Context current = Context.current();
        Span currentSpan = Span.fromContext(current);

        if (!currentSpan.getSpanContext().isValid()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new HashMap<>();
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(current, result, HEADER_SETTER);
        return result;
    }

    @Override
    public TraceOutboxRecordProcessingResult traceOutboxRecordProcessing(OutboxRecord outboxRecord) {
        Set<Entry<String, String>> headerEntries = outboxRecord.getHeaders().entrySet();
        boolean containsTraceInfo = headerEntries.stream().anyMatch(e -> e.getKey().startsWith(INTERNAL_PREFIX));

        if (!containsTraceInfo) {
            return new HeadersOnlyTraceOutboxRecordProcessingResult(outboxRecord.getHeaders());
        }

        // Extract context from outbox record headers
        Context extractedContext = openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.current(), outboxRecord.getHeaders(), HEADER_GETTER);

        // Create outbox span with the extracted context as parent
        Span outboxSpan = tracer.spanBuilder("transactional-outbox")
                .setParent(extractedContext)
                .setStartTimestamp(outboxRecord.getCreated().toEpochMilli(), TimeUnit.MILLISECONDS)
                .startSpan();
        outboxSpan.end();

        // Filter out internal tracing headers from the result
        Map<String, String> newHeaders = headerEntries.stream()
                .filter(entry -> !entry.getKey().startsWith(INTERNAL_PREFIX))
                .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

        // Create processing span for publishing to Kafka with outbox span as parent
        Span processingSpan = tracer.spanBuilder(TO_PREFIX + outboxRecord.getTopic())
                .setParent(extractedContext.with(outboxSpan))
                .setSpanKind(SpanKind.PRODUCER)
                .startSpan();

        // Inject processing span context into headers for Kafka
        Context processingContext = extractedContext.with(processingSpan);
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(processingContext, newHeaders, KAFKA_HEADER_SETTER);

        return new TraceOutboxRecordProcessingResult(newHeaders) {
            @Override
            public void publishCompleted() {
                processingSpan.end();
            }

            @Override
            public void publishFailed(Throwable t) {
                processingSpan.recordException(t);
                processingSpan.end();
            }
        };
    }
}
