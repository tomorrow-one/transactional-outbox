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
package one.tomorrow.transactionaloutbox.publisher;

import jakarta.annotation.Nonnull;
import lombok.AllArgsConstructor;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

import java.util.Map;
import java.util.concurrent.Future;

@AllArgsConstructor
public class KafkaProducerMessagePublisher implements MessagePublisher {

    private final KafkaProducer<String, byte[]> kafkaProducer;

    @Override
    public Future<?> publish(
            Long id,
            String topic,
            String key,
            byte[] payload,
            @Nonnull Map<String, byte[]> headers
    ) {
        Headers kafkaHeaders = new RecordHeaders();
        headers.forEach(kafkaHeaders::add);
        return kafkaProducer.send(
                new ProducerRecord<>(topic, null, key, payload, kafkaHeaders)
        );
    }

    @Override
    public void close() {
        if (kafkaProducer != null) {
            kafkaProducer.close();
        }
    }
}
