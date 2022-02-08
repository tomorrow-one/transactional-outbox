package one.tomorrow.transactionaloutbox.service;

import lombok.AllArgsConstructor;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.service.OutboxService.Header;
import one.tomorrow.transactionaloutbox.test.Sample.SomethingHappened;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static one.tomorrow.transactionaloutbox.service.SampleService.Topics.topic1;

@Service
@AllArgsConstructor
public class SampleService {

	private static final Logger logger = LoggerFactory.getLogger(SampleService.class);

	private OutboxService outboxService;

	@Transactional
	public void doSomething(int id, String name) {
		// Here s.th. else would be done within the transaction, e.g. some entity created.
		// We record this fact with the event that shall be published to interested parties / consumers.
		SomethingHappened event = SomethingHappened.newBuilder()
				.setId(id)
				.setName(name)
				.build();
		OutboxRecord record = outboxService.saveForPublishing(topic1, String.valueOf(id), event);
		logger.info("Stored event [{}] in outbox with id {}, key {} and headers {}", event, record.getId(), record.getKey(), record.getHeaders());
	}

	@Transactional
	public void doSomethingWithAdditionalHeaders(int id, String name, Header...headers) {
		// Here s.th. else would be done within the transaction, e.g. some entity created.
		// We record this fact with the event that shall be published to interested parties / consumers.
		SomethingHappened event = SomethingHappened.newBuilder()
				.setId(id)
				.setName(name)
				.build();
		OutboxRecord record = outboxService.saveForPublishing(topic1, String.valueOf(id), event, headers);
		logger.info("Stored event [{}] in outbox with id {}, key {} and headers {}", event, record.getId(), record.getKey(), record.getHeaders());
	}

	abstract static class Topics {
		public static final String topic1 = "sampleTopic";
	}

}
