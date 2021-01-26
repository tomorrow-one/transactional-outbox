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
