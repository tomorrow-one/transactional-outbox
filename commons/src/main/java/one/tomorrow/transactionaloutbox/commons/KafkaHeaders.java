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

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@SuppressWarnings("unused")
public class KafkaHeaders {

    private static final Logger logger = LoggerFactory.getLogger(KafkaHeaders.class);

    /**
     * The name for the header to store service that published the event.
     * Useful in a migration scenario (an event is going to be published by a different service).
     */
    public static final String HEADERS_SOURCE_NAME = "x-source";
    /**
     * The name for the header to store the sequence as long. Because header values are stored as <code>byte[]</code>,
     * the value is expected to be the big-endian representation of the long in an 8-element byte array.<br/>
     * To transform a long to <code>byte[]</code>, you can use {@link Longs#toByteArray(long)}.<br/>
     * Note: {@link BigInteger#toByteArray()} does not return the appropriate representation!
     */
    public static final String HEADERS_SEQUENCE_NAME = "x-sequence";
    /** The header to store the type of the value, so data can be deserialized to that type. */
    public static final String HEADERS_VALUE_TYPE_NAME = "x-value-type";
    public static final String HEADERS_DLT_SOURCE_NAME = "x-deadletter-source";
    public static final String HEADERS_DLT_RETRY_NAME = "x-deadletter-retry";

    public static Map<String, Object> knownHeaders(Headers headers) {
        Map<String, Object> res = new HashMap<>();
        addHeaderIfPresent(HEADERS_VALUE_TYPE_NAME, String::new, headers, res);
        addHeaderIfPresent(HEADERS_SOURCE_NAME, String::new, headers, res);
        addHeaderIfPresent(HEADERS_SEQUENCE_NAME, Longs::toLong, headers, res);
        addHeaderIfPresent(HEADERS_DLT_SOURCE_NAME, String::new, headers, res);
        addHeaderIfPresent(HEADERS_DLT_RETRY_NAME, Longs::toLong, headers, res);
        return res;
    }

    private static void addHeaderIfPresent(String key, Function<byte[], Object> valueTransformer, Headers headers, Map<String, Object> res) {
        Header header = headers.lastHeader(key);
        if (header != null) {
            try {
                res.put(key, valueTransformer.apply(header.value()));
            } catch (Exception e) {
                logger.warn("Failed to transform byte array {} for key {}: {}", Arrays.toString(header.value()), key, e.getMessage(), e);
            }
        }
    }

}