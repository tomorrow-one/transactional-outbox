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

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer.SpanInScope;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTraceContext;
import io.micrometer.tracing.test.simple.SimpleTracer;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.tracing.TracingService.TraceOutboxRecordProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static java.lang.System.currentTimeMillis;
import static java.time.temporal.ChronoUnit.MILLIS;
import static one.tomorrow.transactionaloutbox.tracing.SimplePropagator.TRACING_SPAN_ID;
import static one.tomorrow.transactionaloutbox.tracing.SimplePropagator.TRACING_TRACE_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class MicrometerTracingServiceTest implements TracingAssertions {

    private SimpleTracer tracer;
    private MicrometerTracingService micrometerTracingService;

    @BeforeEach
    void setUp() {
        tracer = new SimpleTracer();
        micrometerTracingService = new MicrometerTracingService(tracer, new SimplePropagator(tracer));
    }

    @Test
    void tracingHeadersForOutboxRecord_withoutActiveTraceContext_returnsEmptyMap() {
        Map<String, String> headers = micrometerTracingService.tracingHeadersForOutboxRecord();
        assertTrue(headers.isEmpty());
    }

    @Test
    void tracingHeadersForOutboxRecord_withActiveTraceContext_returnsHeaders() {
        Span span = tracer.nextSpan().name("test-span").start();
        try (SpanInScope ignored = tracer.withSpan(span)) {
            Map<String, String> headers = micrometerTracingService.tracingHeadersForOutboxRecord();
            assertFalse(headers.isEmpty());
            assertEquals(span.context().traceId(), headers.get("_internal_:" + TRACING_TRACE_ID));
            assertEquals(span.context().spanId(), headers.get("_internal_:" + TRACING_SPAN_ID));
        } finally {
            span.end();
        }
    }

    @Test
    void traceOutboxRecordProcessing_withValidOutboxRecord_createsAndEndsSpan() {
        OutboxRecord outboxRecord = new OutboxRecord();
        String traceId = "traceId1";
        String spanId = "spanId1";
        outboxRecord.setHeaders(Map.of(
                "some", "header",
                "_internal_:" + TRACING_TRACE_ID, traceId,
                "_internal_:" + TRACING_SPAN_ID, spanId));
        outboxRecord.setCreated(new Timestamp(currentTimeMillis() - 42));

        TraceOutboxRecordProcessingResult result = micrometerTracingService.traceOutboxRecordProcessing(outboxRecord);

        // verify recorded span for the outbox record in the transactional-outbox
        assertEquals(2, tracer.getSpans().size()); // one for the transactional-outbox and one for the processing to Kafka
        SimpleSpan outboxSpan = tracer.getSpans().getFirst();
        assertOutboxSpan(outboxSpan, traceId, spanId, outboxRecord);

        // verify recorded span for the processing to Kafka
        SimpleSpan processingSpan = tracer.getSpans().getLast();
        SimpleTraceContext processingSpanContext = processingSpan.context();
        assertProcessingSpan(processingSpanContext, traceId, outboxSpan.context().spanId());

        // verify returned headers
        Map<String, String> headers = result.getHeaders();
        assertThat(headers, hasEntry("some", "header"));
        assertThat(headers, hasEntry(TRACING_TRACE_ID, traceId));
        assertThat(headers, hasEntry(TRACING_SPAN_ID, processingSpanContext.spanId()));

        // verify that the processing span is ended correctly
        // initially the end timespan is
        assertEquals(Instant.ofEpochMilli(0L), processingSpan.getEndTimestamp());
        Instant before = Instant.now().truncatedTo(MILLIS);
        result.publishCompleted();
        Instant after = Instant.now().truncatedTo(MILLIS);
        assertThat(before, lessThanOrEqualTo(processingSpan.getEndTimestamp()));
        assertThat(after, greaterThanOrEqualTo(processingSpan.getEndTimestamp()));
    }

    @Test
    void traceOutboxRecordProcessing_withoutTraceHeaders_ignoresTracing() {
        OutboxRecord outboxRecord = new OutboxRecord();
        outboxRecord.setHeaders(Map.of("some", "header"));
        outboxRecord.setCreated(new Timestamp(currentTimeMillis()));

        TraceOutboxRecordProcessingResult result = micrometerTracingService.traceOutboxRecordProcessing(outboxRecord);

        assertTrue(tracer.getSpans().isEmpty());
        Map<String, String> headers = result.getHeaders();
        assertEquals(1, headers.size());
        assertEquals("header", headers.get("some"));
    }

}
