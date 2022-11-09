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
package one.tomorrow.transactionaloutbox;

import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class TestUtils {

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

	@NotNull
	public static OutboxRecord newRecord(String topic, String key, String value, Map<String, String> headers) {
		return newRecord(null, topic, key, value, headers);
	}

	@NotNull
	public static OutboxRecord newRecord(Instant processed, String topic, String key, String value, Map<String, String> headers) {
		return new OutboxRecord(
				null,
				null,
				processed,
				topic,
				key,
				value.getBytes(),
				headers
		);
	}

	public static void assertConsumedRecord(OutboxRecord outboxRecord, String headerKey, ConsumerRecord<String, byte[]> kafkaRecord) {
		assertEquals(outboxRecord.getKey(), kafkaRecord.key());
		assertArrayEquals(outboxRecord.getValue(), kafkaRecord.value());
		assertArrayEquals(outboxRecord.getHeaders().get(headerKey).getBytes(), kafkaRecord.headers().lastHeader(headerKey).value());
	}

}
