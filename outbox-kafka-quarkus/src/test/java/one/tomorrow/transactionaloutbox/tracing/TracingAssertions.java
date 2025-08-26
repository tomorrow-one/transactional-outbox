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

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public interface TracingAssertions {

    default void assertOutboxSpan(SpanData outboxSpan, String expectedTraceId, String expectedParentSpanId, OutboxRecord outboxRecord) {
        SpanContext spanContext = outboxSpan.getSpanContext();
        assertEquals(expectedTraceId, spanContext.getTraceId());
        assertEquals(expectedParentSpanId, outboxSpan.getParentSpanId());
        assertEquals("transactional-outbox", outboxSpan.getName());

        // Verify timing - the span should start at the outbox record creation time
        Instant expectedStartTime = outboxRecord.getCreated();
        Instant actualStartTime = Instant.ofEpochMilli(outboxSpan.getStartEpochNanos() / 1_000_000);
        // Allow some tolerance for timing differences
        assertTrue(Math.abs(expectedStartTime.toEpochMilli() - actualStartTime.toEpochMilli()) < 1000,
                   "Start time should be close to outbox record creation time");

        assertTrue(outboxSpan.getEndEpochNanos() > outboxSpan.getStartEpochNanos());
        assertNotNull(spanContext.getSpanId());
    }

    default void assertProcessingSpan(SpanData processingSpan, String expectedTraceId, String expectedParentSpanId, String expectedTopic) {
        SpanContext spanContext = processingSpan.getSpanContext();
        assertEquals(expectedTraceId, spanContext.getTraceId());
        assertEquals(expectedParentSpanId, processingSpan.getParentSpanId());
        assertEquals("To_" + expectedTopic, processingSpan.getName());
        assertEquals(io.opentelemetry.api.trace.SpanKind.PRODUCER, processingSpan.getKind());
        assertNotNull(spanContext.getSpanId());
    }

    default SpanData findSpanByName(InMemorySpanExporter spanExporter, String spanName) {
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        return spans.stream()
                .filter(span -> spanName.equals(span.getName()))

                .findFirst()
                .orElseThrow(() -> new AssertionError("No span found with name: " + spanName));
    }

    default List<SpanData> findSpansByNamePrefix(InMemorySpanExporter spanExporter, String namePrefix) {
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        return spans.stream()
                .filter(span -> span.getName().startsWith(namePrefix))
                .toList();
    }
}
