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

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import one.tomorrow.transactionaloutbox.publisher.EmitterMessagePublisher.EmitterResolver;
import one.tomorrow.transactionaloutbox.publisher.KafkaProducerMessagePublisherFactory.KafkaProducerFactory;

import java.util.Map;

@ApplicationScoped
public class PublisherConfig {

    private final Instance<Map<String, Object>> producerPropsInstance;
    private final Instance<EmitterResolver> emitterResolverInstance;
    private final Instance<KafkaProducerFactory> kafkaProducerFactoryInstance;

    public PublisherConfig(
            @Identifier("default-kafka-broker")
            Instance<Map<String, Object>> producerPropsInstance,
            Instance<EmitterResolver> emitterResolverInstance,
            Instance<KafkaProducerFactory> kafkaProducerFactoryInstance) {
        this.producerPropsInstance = producerPropsInstance;
        this.emitterResolverInstance = emitterResolverInstance;
        this.kafkaProducerFactoryInstance = kafkaProducerFactoryInstance;
    }

    @Produces
    @ApplicationScoped
    public MessagePublisherFactory createMessagePublisherFactory() {
        // Check if Kafka producer props are available and contain bootstrap.servers
        if (producerPropsInstance.isResolvable() && kafkaProducerFactoryInstance.isResolvable()) {
            Map<String, Object> producerProps = producerPropsInstance.get();
            Object bootstrapServers = producerProps.get("bootstrap.servers");
            if (bootstrapServers != null && !((String)bootstrapServers).isEmpty()) {
                return new KafkaProducerMessagePublisherFactory(kafkaProducerFactoryInstance.get());
            }
        }

        // Check if EmitterResolver is available
        if (emitterResolverInstance.isResolvable()) {
            return new EmitterMessagePublisherFactory(emitterResolverInstance.get());
        }

        throw new IllegalStateException("No suitable MessagePublisherFactory can be created. " +
                "Either provide Kafka producer properties with 'bootstrap.servers' or an EmitterResolver.");
    }
}
