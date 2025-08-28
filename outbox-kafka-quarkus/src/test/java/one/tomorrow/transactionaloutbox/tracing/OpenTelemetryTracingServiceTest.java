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
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.tracing.TracingService.TraceOutboxRecordProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.time.temporal.ChronoUnit.MILLIS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.*;

class OpenTelemetryTracingServiceTest implements TracingAssertions {

    private OpenTelemetry openTelemetry;

    private InMemorySpanExporter spanExporter;
    private OpenTelemetryTracingService tracingService;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();

        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(
                    W3CTraceContextPropagator.getInstance()))
                .build();

        tracingService = new OpenTelemetryTracingService(openTelemetry);
        tracer = openTelemetry.getTracer("one.tomorrow.transactional-outbox");
    }

    @Test
    void tracingHeadersForOutboxRecord_withActiveTraceContext_returnsHeaders() {
        // given
        Span span = tracer.spanBuilder("test-span").startSpan();

        try (var ignored = span.makeCurrent()) {
            // when
            Map<String, String> headers = tracingService.tracingHeadersForOutboxRecord();

            // then
            assertFalse(headers.isEmpty());
            String traceparentKey = TracingService.INTERNAL_PREFIX + "traceparent";
            assertTrue(headers.containsKey(traceparentKey));
            String traceparent = headers.get(traceparentKey);
            String expectedTraceId = span.getSpanContext().getTraceId();
            String expectedSpanId = span.getSpanContext().getSpanId();
            // traceparent format: 00-<traceId>-<spanId>-<flags>
            String[] parts = traceparent.split("-");
            assertEquals(4, parts.length);
            assertEquals("00", parts[0]);
            assertEquals(expectedTraceId, parts[1]);
            assertEquals(expectedSpanId, parts[2]);
        } finally {
            span.end();
        }
    }

    @Test
    void tracingHeadersForOutboxRecord_withoutActiveTraceContext_returnsEmptyMap() {
        // when
        Map<String, String> headers = tracingService.tracingHeadersForOutboxRecord();

        // then
        assertTrue(headers.isEmpty());
    }

    @Test
    void traceOutboxRecordProcessing_withValidOutboxRecord_createsAndEndsSpan() {
        // given
        OutboxRecord outboxRecord = new OutboxRecord();
        outboxRecord.setTopic("test-topic");
        outboxRecord.setCreated(Instant.now().minusMillis(42));

        // Provide a synthetic W3C trace context in headers (no extra parent span gets exported)
        String traceId = "0123456789abcdef0123456789abcdef"; // 32 hex
        String parentSpanId = "0123456789abcdef"; // 16 hex
        String traceparent = "00-" + traceId + "-" + parentSpanId + "-01";
        Map<String, String> headers = new HashMap<>();
        headers.put("some", "header");
        headers.put(TracingService.INTERNAL_PREFIX + "traceparent", traceparent);
        outboxRecord.setHeaders(headers);

        // when
        TraceOutboxRecordProcessingResult result = tracingService.traceOutboxRecordProcessing(outboxRecord);

        // then: one finished span (outbox) and one in-flight (processing)
        List<SpanData> finished = spanExporter.getFinishedSpanItems();
        assertEquals(1, finished.size());
        SpanData outboxSpan = finished.get(0);
        assertOutboxSpan(outboxSpan, traceId, parentSpanId, outboxRecord);

        // verify returned headers: user header preserved, no internal headers, and traceparent for processing present
        Map<String, String> resultHeaders = result.getHeaders();
        assertEquals("header", resultHeaders.get("some"));
        assertTrue(resultHeaders.keySet().stream().noneMatch(k -> k.startsWith(TracingService.INTERNAL_PREFIX)));
        assertTrue(resultHeaders.containsKey("traceparent"));
        String processingTraceparent = resultHeaders.get("traceparent");
        String[] processingParts = processingTraceparent.split("-");
        assertEquals(4, processingParts.length);
        assertEquals(traceId, processingParts[1]);
        String processingSpanIdFromHeader = processingParts[2];
        // The processing span should have its own unique ID, not equal to outbox span ID
        assertNotEquals(outboxSpan.getSpanId(), processingSpanIdFromHeader);

        // now end the processing span and verify it gets exported correctly
        Instant before = Instant.now();
        result.publishCompleted();
        Instant after = Instant.now();

        finished = spanExporter.getFinishedSpanItems();
        assertEquals(2, finished.size());
        SpanData processingSpan = finished.get(finished.size() - 1);
        assertProcessingSpan(processingSpan, traceId, outboxSpan.getSpanId(), outboxRecord.getTopic());

        // verify that the processing span is ended correctly
        assertThat(before.toEpochMilli(), lessThanOrEqualTo(processingSpan.getEndEpochNanos() / 1_000_000));
        assertThat(after.toEpochMilli(), greaterThanOrEqualTo(processingSpan.getEndEpochNanos() / 1_000_000));
    }

    @Test
    void traceOutboxRecordProcessing_withoutTraceHeaders_ignoresTracing() {
        // given
        OutboxRecord outboxRecord = new OutboxRecord();
        outboxRecord.setTopic("test-topic");
        outboxRecord.setCreated(Instant.now());

        Map<String, String> headers = new HashMap<>();
        headers.put("some", "header");
        outboxRecord.setHeaders(headers);

        // when
        TraceOutboxRecordProcessingResult result = tracingService.traceOutboxRecordProcessing(outboxRecord);

        // then
        assertTrue(spanExporter.getFinishedSpanItems().isEmpty());
        Map<String, String> resultHeaders = result.getHeaders();
        assertEquals(1, resultHeaders.size());
        assertEquals("header", resultHeaders.get("some"));

        // Complete the processing (should be no-op)
        result.publishCompleted();

        // Still no spans
        assertTrue(spanExporter.getFinishedSpanItems().isEmpty());
    }

    @Test
    void traceOutboxRecordProcessing_whenPublishFails_recordsException() {
        // given
        OutboxRecord outboxRecord = new OutboxRecord();
        outboxRecord.setTopic("test-topic");
        outboxRecord.setCreated(Instant.now());

        String traceId = "fedcba9876543210fedcba9876543210";
        String parentSpanId = "abcdef0123456789";
        String traceparent = "00-" + traceId + "-" + parentSpanId + "-01";
        Map<String, String> headers = new HashMap<>();
        headers.put(TracingService.INTERNAL_PREFIX + "traceparent", traceparent);
        outboxRecord.setHeaders(headers);

        // when
        TraceOutboxRecordProcessingResult result = tracingService.traceOutboxRecordProcessing(outboxRecord);
        RuntimeException exception = new RuntimeException("Test exception");
        result.publishFailed(exception);

        // then - both spans exported and processing span contains an exception event
        List<SpanData> finished = spanExporter.getFinishedSpanItems();
        assertEquals(2, finished.size());
        SpanData processingSpan = finished.get(finished.size() - 1);
        assertEquals(OpenTelemetryTracingService.TO_PREFIX + outboxRecord.getTopic(), processingSpan.getName());
        assertTrue(processingSpan.getEvents().stream().anyMatch(e -> "exception".equals(e.getName())));
    }
}
