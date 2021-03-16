package one.tomorrow.transactionaloutbox.reactive;

import io.r2dbc.postgresql.codec.Json;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;

import java.time.Instant;
import java.util.Map;

public class TestUtils {

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
