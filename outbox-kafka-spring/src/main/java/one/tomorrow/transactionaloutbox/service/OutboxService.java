/**
 * Copyright 2023-2025 Tomorrow GmbH @ https://tomorrow.one
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
import one.tomorrow.transactionaloutbox.tracing.TracingService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

import static one.tomorrow.transactionaloutbox.commons.Maps.merge;

@Service
@AllArgsConstructor
public class OutboxService {

    private OutboxRepository repository;
    private TracingService tracingService;

    public OutboxRecord saveForPublishing(String topic, String key, byte[] value) {
        return saveForPublishing(topic, key, value, null);
    }

    public OutboxRecord saveForPublishing(String topic, String key, byte[] value, Map<String, String> headerMap) {
        Map<String, String> tracingHeaders = tracingService.tracingHeadersForOutboxRecord();
        Map<String, String> headers = merge(headerMap, tracingHeaders);
        OutboxRecord outboxRecord = OutboxRecord.builder()
                .topic(topic)
                .key(key)
                .value(value)
                .headers(headers)
                .build();
        repository.persist(outboxRecord);
        return outboxRecord;
    }

}
