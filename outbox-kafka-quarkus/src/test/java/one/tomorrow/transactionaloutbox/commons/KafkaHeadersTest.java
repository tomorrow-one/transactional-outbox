/**
 * Copyright 2022 Tomorrow GmbH @ https://tomorrow.one
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
package one.tomorrow.transactionaloutbox.commons;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.*;
import static one.tomorrow.transactionaloutbox.commons.Longs.toByteArray;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KafkaHeadersTest {

    @Test
    void knownHeadersShouldReturnGivenHeaders() {
        RecordHeaders headers = recordHeaders(HEADERS_SEQUENCE_NAME, toByteArray(42));
        assertEquals(mapOf(HEADERS_SEQUENCE_NAME, 42L), knownHeaders(headers));

        headers = recordHeaders(HEADERS_SOURCE_NAME, "foo".getBytes());
        assertEquals(mapOf(HEADERS_SOURCE_NAME, "foo"), knownHeaders(headers));

        headers = recordHeaders(HEADERS_VALUE_TYPE_NAME, "foo".getBytes());
        assertEquals(mapOf(HEADERS_VALUE_TYPE_NAME, "foo"), knownHeaders(headers));

        headers = recordHeaders(HEADERS_DLT_SOURCE_NAME, "foo".getBytes());
        assertEquals(mapOf(HEADERS_DLT_SOURCE_NAME, "foo"), knownHeaders(headers));

        headers = recordHeaders(HEADERS_DLT_RETRY_NAME, toByteArray(42));
        assertEquals(mapOf(HEADERS_DLT_RETRY_NAME, 42L), knownHeaders(headers));

        headers = new RecordHeaders();
        headers.add(HEADERS_SEQUENCE_NAME, toByteArray(42));
        headers.add(HEADERS_SOURCE_NAME, "foo".getBytes());
        headers.add(HEADERS_VALUE_TYPE_NAME, "bar".getBytes());
        headers.add(HEADERS_DLT_SOURCE_NAME, "baz".getBytes());
        headers.add(HEADERS_DLT_RETRY_NAME, toByteArray(16));
        headers.add("some-header", "baz".getBytes());
        assertEquals(
                mapOf(
                        HEADERS_SEQUENCE_NAME, 42L,
                        HEADERS_SOURCE_NAME, "foo",
                        HEADERS_VALUE_TYPE_NAME, "bar",
                        HEADERS_DLT_SOURCE_NAME, "baz",
                        HEADERS_DLT_RETRY_NAME, 16L
                ),
                knownHeaders(headers)
        );
    }

    @Test
    void knownHeadersShouldGracefullyHandleBadSequenceRepresentation() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(HEADERS_SEQUENCE_NAME, new byte[0]);
        headers.add(HEADERS_SOURCE_NAME, "foo".getBytes());
        assertEquals(mapOf(HEADERS_SOURCE_NAME, "foo"), knownHeaders(headers));
    }

    private RecordHeaders recordHeaders(String key, byte[] value) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(key, value);
        return headers;
    }

    private Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> result = new HashMap<>();
        result.put(key, value);
        return result;
    }

    private Map<String, Object> mapOf(String key1, Object value1,
                                      String key2, Object value2,
                                      String key3, Object value3,
                                      String key4, Object value4,
                                      String key5, Object value5) {
        Map<String, Object> result = new HashMap<>();
        result.put(key1, value1);
        result.put(key2, value2);
        result.put(key3, value3);
        result.put(key4, value4);
        result.put(key5, value5);
        return result;
    }

}