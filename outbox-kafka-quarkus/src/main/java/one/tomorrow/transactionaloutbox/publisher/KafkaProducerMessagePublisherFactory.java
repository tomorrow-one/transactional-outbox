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
package one.tomorrow.transactionaloutbox.publisher;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.AllArgsConstructor;
import org.apache.kafka.clients.producer.KafkaProducer;

@ApplicationScoped
@DefaultBean
@AllArgsConstructor
public class KafkaProducerMessagePublisherFactory implements MessagePublisherFactory {

    /**
     * Creates a new {@link KafkaProducer}. By default, {@link DefaultKafkaProducerFactory}
     * is used, but you can supply your own implementation.
     * Just ensure that the producer is configured with <code>enable.idempotence=true</code> for strict ordering.
     */
    @FunctionalInterface
    public interface KafkaProducerFactory {
        KafkaProducer<String, byte[]> createKafkaProducer();
    }

    private final KafkaProducerFactory kafkaProducerFactory;

    @Override
    public MessagePublisher create() {
        return new KafkaProducerMessagePublisher(kafkaProducerFactory.createKafkaProducer());
    }

    @Override
    public String toString() {
        return "KafkaProducerMessageFactory{producerFactory=" + kafkaProducerFactory + '}';
    }

}
