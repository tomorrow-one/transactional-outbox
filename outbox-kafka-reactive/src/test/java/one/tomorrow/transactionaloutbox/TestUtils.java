package one.tomorrow.transactionaloutbox;

import io.r2dbc.postgresql.codec.Json;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.time.Instant;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class TestUtils {

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

	public static void assertConsumedRecord(OutboxRecord outboxRecord, String headerKey, ConsumerRecord<String, byte[]> kafkaRecord) {
		assertEquals(outboxRecord.getKey(), kafkaRecord.key());
		assertArrayEquals(outboxRecord.getValue(), kafkaRecord.value());
		assertArrayEquals(outboxRecord.getHeadersAsMap().get(headerKey).getBytes(), kafkaRecord.headers().lastHeader(headerKey).value());
	}

}
