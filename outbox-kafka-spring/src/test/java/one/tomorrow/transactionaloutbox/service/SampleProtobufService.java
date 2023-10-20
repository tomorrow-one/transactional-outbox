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
package one.tomorrow.transactionaloutbox.service;

import lombok.AllArgsConstructor;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.service.ProtobufOutboxService.Header;
import one.tomorrow.transactionaloutbox.test.Sample.SomethingHappened;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static one.tomorrow.transactionaloutbox.service.SampleProtobufService.Topics.topic1;

@Service
@AllArgsConstructor
public class SampleProtobufService {

	private static final Logger logger = LoggerFactory.getLogger(SampleProtobufService.class);

	private ProtobufOutboxService outboxService;

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
		public static final String topic1 = "sampleProtobufTopic";
	}

}
