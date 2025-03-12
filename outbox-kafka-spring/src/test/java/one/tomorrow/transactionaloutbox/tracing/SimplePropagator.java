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
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleSpanBuilder;
import io.micrometer.tracing.test.simple.SimpleTraceContext;
import io.micrometer.tracing.test.simple.SimpleTracer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@RequiredArgsConstructor
public class SimplePropagator implements Propagator {

    public static final String TRACING_TRACE_ID = "traceId";
    public static final String TRACING_SPAN_ID = "spanId";
    private final SimpleTracer tracer;

    @Override
    @NotNull
    public List<String> fields() {
        return List.of(TRACING_TRACE_ID, TRACING_SPAN_ID);
    }

    @Override
    public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
        setter.set(carrier, TRACING_TRACE_ID, context.traceId());
        setter.set(carrier, TRACING_SPAN_ID, context.spanId());
    }

    @Override
    @NotNull
    public <C> Span.Builder extract(@NotNull C carrier, Getter<C> getter) {
        SimpleTraceContext traceContext = new SimpleTraceContext();

        String traceId = getter.get(carrier, TRACING_TRACE_ID);
        if (traceId != null)
            traceContext.setTraceId(traceId);

        String spanId = getter.get(carrier, TRACING_SPAN_ID);
        if (spanId != null)
            traceContext.setSpanId(spanId);

        Span.Builder builder = new SimpleSpanBuilder(tracer);
        builder.setParent(traceContext);
        return builder;
    }
}
