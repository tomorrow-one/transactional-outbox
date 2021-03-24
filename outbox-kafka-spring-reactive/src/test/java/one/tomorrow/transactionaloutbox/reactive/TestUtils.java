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
