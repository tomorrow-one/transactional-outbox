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
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static one.tomorrow.transactionaloutbox.service.SampleService.Topics.topic1;

@Service
@AllArgsConstructor
public class SampleService {

    private static final Logger logger = LoggerFactory.getLogger(SampleService.class);

    private OutboxService outboxService;

    @Transactional
    public void doSomething(int id, String something) {
        // Here s.th. else would be done within the transaction, e.g. some entity created.
        // We record this fact with the event that shall be published to interested parties / consumers.
        OutboxRecord record = outboxService.saveForPublishing(topic1, String.valueOf(id), something.getBytes());
        logger.info("Stored event [{}] in outbox with id {} and key {}", something, record.getId(), record.getKey());
    }

    @Transactional
    public void doSomethingWithAdditionalHeaders(int id, String something, Header...headers) {
        // Here s.th. else would be done within the transaction, e.g. some entity created.
        // We record this fact with the event that shall be published to interested parties / consumers.
        Map<String, String> headerMap = Arrays.stream(headers)
                .collect(Collectors.toMap(Header::getKey, Header::getValue));
        OutboxRecord record = outboxService.saveForPublishing(topic1, String.valueOf(id), something.getBytes(), headerMap);
        logger.info("Stored event [{}] in outbox with id {}, key {} and headers {}", something, record.getId(), record.getKey(), record.getHeaders());
    }

    @Getter
    @RequiredArgsConstructor
    public static class Header {
        private final String key;
        private final String value;
    }

    abstract static class Topics {
        public static final String topic1 = "sampleTopic";
    }

}
