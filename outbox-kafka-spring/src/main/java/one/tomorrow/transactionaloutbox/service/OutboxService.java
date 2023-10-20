/**
 * Copyright 2023 Tomorrow GmbH @ https://tomorrow.one
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
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@AllArgsConstructor
public class OutboxService {

	private OutboxRepository repository;

    public OutboxRecord saveForPublishing(String topic, String key, byte[] value) {
        return saveForPublishing(topic, key, value, null);
    }

	public OutboxRecord saveForPublishing(String topic, String key, byte[] value, Map<String, String> headerMap) {
		OutboxRecord record = OutboxRecord.builder()
				.topic(topic)
				.key(key)
				.value(value)
				.headers(headerMap)
				.build();
		repository.persist(record);
		return record;
	}

}
