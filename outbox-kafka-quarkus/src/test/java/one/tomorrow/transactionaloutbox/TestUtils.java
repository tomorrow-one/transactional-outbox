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
package one.tomorrow.transactionaloutbox;

import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class TestUtils {

    /**
     * Creates a new OutboxRecord with the given parameters
     */
    public static OutboxRecord newRecord(String topic, String key, String value, Map<String, String> headers) {
        return newRecord(null, topic, key, value, headers);
    }

    /**
     * Creates a new OutboxRecord with the given parameters
     */
    public static OutboxRecord newRecord(Instant processed, String topic, String key, String value, Map<String, String> headers) {
        return OutboxRecord.builder()
                .processed(processed)
                .topic(topic)
                .key(key)
                .value(value.getBytes(StandardCharsets.UTF_8))
                .headers(headers)
                .build();
    }

    @NotNull
    public static Map<String, String> newHeaders(String ... keyValue) {
        Map<String, String> headers1 = new HashMap<>();
        if(keyValue.length % 2 != 0)
            throw new IllegalArgumentException("KeyValue must be a list of pairs");
        for (int i = 0; i < keyValue.length; i += 2) {
            headers1.put(keyValue[i], keyValue[i + 1]);
        }
        return headers1;
    }
}
