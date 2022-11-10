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

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import com.google.protobuf.Message;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_VALUE_TYPE_NAME;

@Service
@AllArgsConstructor
public class OutboxService {

	private OutboxRepository repository;

	public <T extends Message> OutboxRecord saveForPublishing(String topic, String key, T event, Header...headers) {
		byte[] value = event.toByteArray();
		Header valueType = new Header(HEADERS_VALUE_TYPE_NAME, event.getDescriptorForType().getFullName());
		Map<String, String> headerMap = Stream.concat(Stream.of(valueType), Arrays.stream(headers))
				.collect(Collectors.toMap(Header::getKey, Header::getValue));
		OutboxRecord record = OutboxRecord.builder()
				.topic(topic)
				.key(key)
				.value(value)
				.headers(headerMap)
				.build();
		repository.persist(record);
		return record;
	}

	@Getter
	@RequiredArgsConstructor
	public static class Header {
		private final String key;
		private final String value;
	}

}
