package one.tomorrow.transactionaloutbox.reactive.service;

import com.google.protobuf.Message;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static one.tomorrow.kafka.core.KafkaHeaders.HEADERS_VALUE_TYPE_NAME;
import static one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord.toJson;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_MANDATORY;

@Service
public class OutboxService {

	private final OutboxRepository repository;
	private final TransactionalOperator mandatoryTxOperator;

	public OutboxService(OutboxRepository repository, ReactiveTransactionManager tm) {
		this.repository = repository;

		DefaultTransactionDefinition txDefinition = new DefaultTransactionDefinition();
		txDefinition.setPropagationBehavior(PROPAGATION_MANDATORY);
		mandatoryTxOperator = TransactionalOperator.create(tm, txDefinition);
	}

	public <T extends Message> Mono<OutboxRecord> saveForPublishing(String topic, String key, T event) {
		Map<String, String> headers = Map.of(HEADERS_VALUE_TYPE_NAME, event.getDescriptorForType().getFullName());
		OutboxRecord record = OutboxRecord.builder()
				.topic(topic)
				.key(key)
				.value(event.toByteArray())
				.headers(toJson(headers))
				.created(Instant.now())
				.build();
		return repository.save(record).as(mandatoryTxOperator::transactional);
	}

}
