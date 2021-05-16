package one.tomorrow.transactionaloutbox.service;

import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import com.google.protobuf.Message;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

import static one.tomorrow.kafka.core.KafkaHeaders.HEADERS_VALUE_TYPE_NAME;

@Service
@AllArgsConstructor
public class OutboxService {

	private OutboxRepository repository;

	public <T extends Message> OutboxRecord saveForPublishing(String topic, String key, T event) {
		byte[] value = event.toByteArray();
		Map<String, String> headers = new HashMap<>(1);
		headers.put(HEADERS_VALUE_TYPE_NAME, event.getDescriptorForType().getFullName());
		OutboxRecord record = OutboxRecord.builder()
				.topic(topic)
				.key(key)
				.value(value)
				.headers(headers)
				.build();
		repository.persist(record);
		return record;
	}

}
