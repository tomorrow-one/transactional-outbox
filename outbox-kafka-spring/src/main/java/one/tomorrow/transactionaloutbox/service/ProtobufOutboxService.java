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

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Internal;
import com.google.protobuf.Message;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import one.tomorrow.transactionaloutbox.commons.spring.ConditionalOnClass;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_VALUE_TYPE_NAME;

@Service
@ConditionalOnClass(Message.class)
@AllArgsConstructor
public class ProtobufOutboxService {

    private OutboxService outboxService;

    /**
     * Save the message/event (as byte array), setting the {@link one.tomorrow.transactionaloutbox.commons.KafkaHeaders#HEADERS_VALUE_TYPE_NAME}
     * to the fully qualified name of the message descriptor.
     *
     * @param topic the Kafka topic to send the record to
     * @param key the key used for the Kafka record (can be null)
     * @param event the message/event used as value for the Kafka record, must not be null; to publish a tombstone use
     *              {@link #saveForPublishing(String, String, Class, Message, Header...)}
     * @param headers additional headers used for the Kafka record
     * @return the persisted outbox record
     */
    public <T extends Message> OutboxRecord saveForPublishing(String topic, String key, T event, Header...headers) {
        Objects.requireNonNull(event, "event must not be null, to publish a tombstone pass the event type and a null event");
        return save(topic, key, event.getDescriptorForType(), event.toByteArray(), headers);
    }

    /**
     * Save the message/event (as byte array), setting the {@link one.tomorrow.transactionaloutbox.commons.KafkaHeaders#HEADERS_VALUE_TYPE_NAME}
     * to the fully qualified name of the message descriptor of the given event type.
     * If the event is null, a tombstone is published, e.g. to delete an entry from a log-compacted topic.
     *
     * @param topic the Kafka topic to send the record to
     * @param key the key used for the Kafka record (can be null)
     * @param eventType the type of the message/event, used to determine the value type header
     * @param event the message/event used as value for the Kafka record (can be null to publish a tombstone)
     * @param headers additional headers used for the Kafka record
     * @return the persisted outbox record
     */
    public <T extends Message> OutboxRecord saveForPublishing(String topic, String key, Class<T> eventType, T event, Header...headers) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        if (event == null)
            return save(topic, key, Internal.getDefaultInstance(eventType).getDescriptorForType(), null, headers);
        return save(topic, key, event.getDescriptorForType(), event.toByteArray(), headers);
    }

    private OutboxRecord save(String topic, String key, Descriptor descriptor, byte[] value, Header...headers) {
        Header valueType = new Header(HEADERS_VALUE_TYPE_NAME, descriptor.getFullName());
        Map<String, String> headerMap = Stream.concat(Stream.of(valueType), Arrays.stream(headers))
                .collect(Collectors.toMap(Header::getKey, Header::getValue));
        return outboxService.saveForPublishing(topic, key, value, headerMap);
    }

    @Getter
    @RequiredArgsConstructor
    public static class Header {
        private final String key;
        private final String value;
    }

}
