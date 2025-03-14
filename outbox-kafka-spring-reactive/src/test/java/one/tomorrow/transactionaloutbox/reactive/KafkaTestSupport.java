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
package one.tomorrow.transactionaloutbox.reactive;

import one.tomorrow.transactionaloutbox.commons.CommonKafkaTestSupport;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.reactive.service.DefaultKafkaProducerFactory;
import one.tomorrow.transactionaloutbox.reactive.tracing.TracingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.Map;

import static one.tomorrow.transactionaloutbox.commons.CommonKafkaTestSupport.producerProps;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SOURCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.Longs.toLong;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public interface KafkaTestSupport extends CommonKafkaTestSupport<byte[]> {

    static DefaultKafkaProducerFactory producerFactory() {
        return producerFactory(producerProps());
    }

    static DefaultKafkaProducerFactory producerFactory(Map<String, Object> producerProps) {
        return new DefaultKafkaProducerFactory(producerProps);
    }

    static void assertConsumedRecord(OutboxRecord outboxRecord, String sourceHeaderValue, ConsumerRecord<String, byte[]> kafkaRecord) {
        assertEquals(
                outboxRecord.getId().longValue(),
                toLong(kafkaRecord.headers().lastHeader(HEADERS_SEQUENCE_NAME).value()),
                "OutboxRecord id and " + HEADERS_SEQUENCE_NAME + " headers do not match"
        );
        assertArrayEquals(sourceHeaderValue.getBytes(), kafkaRecord.headers().lastHeader(HEADERS_SOURCE_NAME).value());
        outboxRecord.getHeadersAsMap().forEach((key, value) -> {
            if (!key.startsWith(TracingService.INTERNAL_PREFIX))
                assertArrayEquals(value.getBytes(), kafkaRecord.headers().lastHeader(key).value());
        });
        assertEquals(outboxRecord.getKey(), kafkaRecord.key());
        assertArrayEquals(outboxRecord.getValue(), kafkaRecord.value());
    }

}
