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

import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTraceContext;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;

import java.time.temporal.ChronoUnit;

import static java.time.temporal.ChronoUnit.MILLIS;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

public interface TracingAssertions {

    default void assertOutboxSpan(SimpleSpan outboxSpan, String traceId, String parentId, OutboxRecord outboxRecord) {
        SimpleTraceContext outboxSpanContext = outboxSpan.context();
        assertThat(outboxSpanContext.traceId(), is(equalTo(traceId)));
        assertThat(outboxSpanContext.parentId(), is(equalTo(parentId)));
        assertThat(outboxSpan.getStartTimestamp().truncatedTo(MILLIS), is(equalTo(outboxRecord.getCreated().truncatedTo(MILLIS))));
        assertThat(outboxSpan.getEndTimestamp(), is(greaterThan(outboxRecord.getCreated())));
        assertThat(outboxSpanContext.spanId(), is(notNullValue()));
    }

    default void assertProcessingSpan(SimpleTraceContext processingSpanContext, String traceId, String parentId) {
        assertThat(processingSpanContext.traceId(), is(equalTo(traceId)));
        assertThat(processingSpanContext.parentId(), is(equalTo(parentId)));
        assertThat(processingSpanContext.spanId(), is(notNullValue()));
    }

}
