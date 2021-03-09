package one.tomorrow.transactionaloutbox.service;

import com.google.protobuf.Message;
import lombok.AllArgsConstructor;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

import static one.tomorrow.kafka.core.KafkaConstants.HEADERS_VALUE_TYPE_NAME;
import static one.tomorrow.transactionaloutbox.model.OutboxRecord.toJson;

@Service
@AllArgsConstructor
public class OutboxService {

	private final OutboxRepository repository;

	public <T extends Message> Mono<OutboxRecord> saveForPublishing(String topic, String key, T event) {
		Map<String, String> headers = Map.of(HEADERS_VALUE_TYPE_NAME, event.getDescriptorForType().getFullName());
		OutboxRecord record = OutboxRecord.builder()
				.topic(topic)
				.key(key)
				.value(event.toByteArray())
				.headers(toJson(headers))
				.build();
		return repository.save(record);
	}

}
