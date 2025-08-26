/**
 * Copyright 2025 Tomorrow GmbH @ https://tomorrow.one
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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.tracing.TracingService;

import java.util.HashMap;
import java.util.Map;

import static jakarta.transaction.Transactional.TxType.MANDATORY;

@ApplicationScoped
public class OutboxService {

    private final OutboxRepository repository;
    private final TracingService tracingService;

    @Inject
    public OutboxService(OutboxRepository repository, TracingService tracingService) {
        this.repository = repository;
        this.tracingService = tracingService;
    }

    /**
     * Persist a record to the outbox table, must be called inside a transaction context.
     *
     * @param topic the Kafka topic to send the record to
     * @param key the key used for the Kafka record (can be null)
     * @param value the value used for the Kafka record
     * @return the ID of the persisted outbox record
     */
    @Transactional(MANDATORY)
    public OutboxRecord saveForPublishing(String topic, String key, byte[] value) {
        return saveForPublishing(topic, key, value, null);
    }

    /**
     * Persist a record to the outbox table, must be called inside a transaction context.
     *
     * @param topic the Kafka topic to send the record to
     * @param key the key used for the Kafka record (can be null)
     * @param value the value used for the Kafka record
     * @param headers the headers used for the Kafka record (can be null)
     * @return the ID of the persisted outbox record
     */
    @Transactional(MANDATORY)
    public OutboxRecord saveForPublishing(String topic, String key, byte[] value, Map<String, String> headers) {
        Map<String, String> tracingHeaders = tracingService.tracingHeadersForOutboxRecord();
        Map<String, String> allHeaders = merge(headers, tracingHeaders);
        OutboxRecord outboxRecord = OutboxRecord.builder()
                .topic(topic)
                .key(key)
                .value(value)
                .headers(allHeaders)
                .build();
        repository.persist(outboxRecord);
        return outboxRecord;
    }

    public static Map<String, String> merge(Map<String, String> map1, Map<String, String> map2) {
        if (isNullOrEmpty(map1))
            return map2;
        if (isNullOrEmpty(map2))
            return map1;
        Map<String, String> result = new HashMap<>(map1);
        result.putAll(map2);
        return result;
    }

    public static boolean isNullOrEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

}
