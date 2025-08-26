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
package one.tomorrow.transactionaloutbox.service;

import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.tracing.TracingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxServiceTest {

    @Mock
    private OutboxRepository repository;

    @Mock
    private TracingService tracingService;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        outboxService = new OutboxService(repository, tracingService);
    }

    @Test
    void should_SaveOutboxRecord_WithoutHeaders() {
        // given
        Map<String, String> traceHeaders = Map.of();
        when(tracingService.tracingHeadersForOutboxRecord()).thenReturn(traceHeaders);
        String topic = "test-topic";
        String key = "test-key";
        byte[] value = "test-value".getBytes();

        // when
        OutboxRecord result = outboxService.saveForPublishing(topic, key, value);

        // then
        assertNotNull(result);
        assertEquals(topic, result.getTopic());
        assertEquals(key, result.getKey());
        assertArrayEquals(value, result.getValue());
        assertEquals(traceHeaders, result.getHeaders());

        verify(repository).persist(any(OutboxRecord.class));
        verify(tracingService).tracingHeadersForOutboxRecord();
    }

    @Test
    void should_SaveOutboxRecord_WithUserHeaders() {
        // given
        when(tracingService.tracingHeadersForOutboxRecord()).thenReturn(Map.of());
        String topic = "test-topic";
        String key = "test-key";
        byte[] value = "test-value".getBytes();
        Map<String, String> userHeaders = Map.of("user-header", "user-value");

        // when
        OutboxRecord result = outboxService.saveForPublishing(topic, key, value, userHeaders);

        // then
        assertNotNull(result);
        assertEquals(topic, result.getTopic());
        assertEquals(key, result.getKey());
        assertArrayEquals(value, result.getValue());
        assertEquals(userHeaders, result.getHeaders());

        verify(repository).persist(any(OutboxRecord.class));
        verify(tracingService).tracingHeadersForOutboxRecord();
    }

    @Test
    void should_SaveOutboxRecord_WithTracingHeaders() {
        // given
        Map<String, String> tracingHeaders = Map.of(
                "_internal_:traceparent", "00-trace-id-span-id-01",
                "_internal_:tracestate", "vendor=value"
        );
        when(tracingService.tracingHeadersForOutboxRecord()).thenReturn(tracingHeaders);
        String topic = "test-topic";
        String key = "test-key";
        byte[] value = "test-value".getBytes();

        // when
        OutboxRecord result = outboxService.saveForPublishing(topic, key, value);

        // then
        assertNotNull(result);
        assertEquals(topic, result.getTopic());
        assertEquals(key, result.getKey());
        assertArrayEquals(value, result.getValue());
        assertEquals(tracingHeaders, result.getHeaders());

        verify(repository).persist(any(OutboxRecord.class));
        verify(tracingService).tracingHeadersForOutboxRecord();
    }

    @Test
    void should_SaveOutboxRecord_WithBothUserAndTracingHeaders() {
        // given
        Map<String, String> tracingHeaders = Map.of(
                "_internal_:traceparent", "00-trace-id-span-id-01"
        );
        when(tracingService.tracingHeadersForOutboxRecord()).thenReturn(tracingHeaders);
        String topic = "test-topic";
        String key = "test-key";
        byte[] value = "test-value".getBytes();
        Map<String, String> userHeaders = Map.of("user-header", "user-value");

        // when
        OutboxRecord result = outboxService.saveForPublishing(topic, key, value, userHeaders);

        // then
        assertNotNull(result);
        assertEquals(topic, result.getTopic());
        assertEquals(key, result.getKey());
        assertArrayEquals(value, result.getValue());

        Map<String, String> expectedHeaders = new HashMap<>(userHeaders);
        expectedHeaders.putAll(tracingHeaders);
        assertEquals(expectedHeaders, result.getHeaders());

        verify(repository).persist(any(OutboxRecord.class));
        verify(tracingService).tracingHeadersForOutboxRecord();
    }

    @Test
    void should_OverrideUserHeaders_WithTracingHeaders_WhenSameKey() {
        // given
        Map<String, String> tracingHeaders = Map.of(
                "same-key", "tracing-value"
        );
        when(tracingService.tracingHeadersForOutboxRecord()).thenReturn(tracingHeaders);
        String topic = "test-topic";
        String key = "test-key";
        byte[] value = "test-value".getBytes();
        Map<String, String> userHeaders = Map.of("same-key", "user-value");

        // when
        OutboxRecord result = outboxService.saveForPublishing(topic, key, value, userHeaders);

        // then
        assertNotNull(result);
        assertEquals(topic, result.getTopic());
        assertEquals(key, result.getKey());
        assertArrayEquals(value, result.getValue());
        assertEquals("tracing-value", result.getHeaders().get("same-key"));

        verify(repository).persist(any(OutboxRecord.class));
        verify(tracingService).tracingHeadersForOutboxRecord();
    }
}
