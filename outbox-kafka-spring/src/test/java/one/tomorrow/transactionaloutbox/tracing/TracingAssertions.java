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

import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTraceContext;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;

import static org.junit.jupiter.api.Assertions.*;

public interface TracingAssertions {

    default void assertOutboxSpan(SimpleSpan outboxSpan, String traceId, String parentId, OutboxRecord outboxRecord) {
        SimpleTraceContext outboxSpanContext = outboxSpan.context();
        assertEquals(traceId, outboxSpanContext.traceId());
        assertEquals(parentId, outboxSpanContext.parentId());
        assertEquals(outboxRecord.getCreated().toInstant(), outboxSpan.getStartTimestamp());
        assertTrue(outboxSpan.getEndTimestamp().isAfter(outboxRecord.getCreated().toInstant()));
        assertNotNull(outboxSpanContext.spanId());
    }

    default void assertProcessingSpan(SimpleTraceContext processingSpanContext, String traceId, String parentId) {
        assertEquals(traceId, processingSpanContext.traceId());
        assertEquals(parentId, processingSpanContext.parentId());
        assertNotNull(processingSpanContext.spanId());
    }

}
