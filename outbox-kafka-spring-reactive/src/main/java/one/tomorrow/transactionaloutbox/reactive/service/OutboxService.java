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
package one.tomorrow.transactionaloutbox.reactive.service;

import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.reactive.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.reactive.tracing.TracingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static one.tomorrow.transactionaloutbox.commons.Maps.merge;
import static one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord.toJson;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_MANDATORY;

@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final TracingService tracingService;
    private final TransactionalOperator mandatoryTxOperator;

    public OutboxService(OutboxRepository repository, ReactiveTransactionManager tm, TracingService tracingService) {
        this.repository = repository;
        this.tracingService = tracingService;

        DefaultTransactionDefinition txDefinition = new DefaultTransactionDefinition();
        txDefinition.setPropagationBehavior(PROPAGATION_MANDATORY);
        mandatoryTxOperator = TransactionalOperator.create(tm, txDefinition);
    }

    public Mono<OutboxRecord> saveForPublishing(String topic, String key, byte[] event) {
        return saveForPublishing(topic, key, event, null);
    }

    public Mono<OutboxRecord> saveForPublishing(String topic, String key, byte[] event, Map<String, String> headerMap) {
        Map<String, String> tracingHeaders = tracingService.tracingHeadersForOutboxRecord();
        Map<String, String> headers = merge(headerMap, tracingHeaders);
        OutboxRecord outboxRecord = OutboxRecord.builder()
                .topic(topic)
                .key(key)
                .value(event)
                .headers(toJson(headers))
                .created(Instant.now())
                .build();
        return repository.save(outboxRecord).as(mandatoryTxOperator::transactional);
    }

}
