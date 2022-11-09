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
package one.tomorrow.transactionaloutbox.reactive;

import io.r2dbc.postgresql.codec.Json;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;

import java.time.Instant;
import java.util.Map;
import java.util.Random;

public class TestUtils {

	private static final Random RANDOM = new Random();

	public static boolean randomBoolean() {
		return RANDOM.nextBoolean();
	}

	public static int randomInt(int bound) {
		return RANDOM.nextInt(bound);
	}

	public static OutboxRecord newRecord(String topic, String key, String value) {
		return newRecord(topic, key, value, null);
	}

	public static OutboxRecord newRecord(String topic, String key, String value, Map<String, String> headers) {
		return newRecord(null, topic, key, value, headers);
	}

	public static OutboxRecord newRecord(Instant processed, String topic, String key, String value, Map<String, String> headers) {
		Json json = OutboxRecord.toJson(headers);
		return new OutboxRecord(
				null,
				Instant.now(),
				processed,
				topic,
				key,
				value.getBytes(),
				json
		);
	}

}
