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

import jakarta.enterprise.inject.Instance;
import one.tomorrow.transactionaloutbox.publisher.EmitterMessagePublisher.EmitterResolver;
import one.tomorrow.transactionaloutbox.publisher.KafkaProducerMessagePublisherFactory.KafkaProducerFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublisherConfigTest {

    private final Instance<Map<String, Object>> producerPropsInstance = mock();
    private final Instance<EmitterResolver> emitterResolverInstance = mock();
    private final Instance<KafkaProducerFactory> kafkaProducerFactoryInstance = mock();
    private final PublisherConfig publisherConfig = new PublisherConfig(
            producerPropsInstance, emitterResolverInstance, kafkaProducerFactoryInstance
    );

    @Test
    void returnsKafkaProducerFactoryWhenProducerPropsAreValid() {
        Map<String, Object> producerProps = Map.of("bootstrap.servers", "localhost:9092");
        when(producerPropsInstance.isResolvable()).thenReturn(true);
        when(producerPropsInstance.get()).thenReturn(producerProps);
        when(kafkaProducerFactoryInstance.isResolvable()).thenReturn(true);

        assertInstanceOf(KafkaProducerMessagePublisherFactory.class, publisherConfig.createMessagePublisherFactory());
    }

    @Test
    void returnsEmitterMessagePublisherFactoryWhenEmitterResolverIsAvailable() {
        when(producerPropsInstance.isResolvable()).thenReturn(false);
        when(emitterResolverInstance.isResolvable()).thenReturn(true);

        assertInstanceOf(EmitterMessagePublisherFactory.class, publisherConfig.createMessagePublisherFactory());
    }

    @Test
    void returnsEmitterMessagePublisherFactoryWhenProducerPropsLackBootstrapServersAndEmitterResolverIsAvailable() {
        Map<String, Object> producerProps = Map.of("some.other.config", "value");
        when(producerPropsInstance.isResolvable()).thenReturn(true);
        when(producerPropsInstance.get()).thenReturn(producerProps);
        when(kafkaProducerFactoryInstance.isResolvable()).thenReturn(true);
        when(emitterResolverInstance.isResolvable()).thenReturn(true);

        assertInstanceOf(EmitterMessagePublisherFactory.class, publisherConfig.createMessagePublisherFactory());
    }

    @Test
    void throwsExceptionWhenNoFactoryCanBeCreated() {
        when(producerPropsInstance.isResolvable()).thenReturn(false);
        when(emitterResolverInstance.isResolvable()).thenReturn(false);
        when(kafkaProducerFactoryInstance.isResolvable()).thenReturn(false);

        assertThrows(IllegalStateException.class, publisherConfig::createMessagePublisherFactory);
    }

    @Test
    void throwsExceptionWhenProducerPropsLackBootstrapServersAndNoEmitterResolverIsAvailable() {
        Map<String, Object> producerProps = Map.of("some.other.config", "value");
        when(producerPropsInstance.isResolvable()).thenReturn(true);
        when(producerPropsInstance.get()).thenReturn(producerProps);
        when(kafkaProducerFactoryInstance.isResolvable()).thenReturn(true);
        when(emitterResolverInstance.isResolvable()).thenReturn(false);

        assertThrows(IllegalStateException.class, publisherConfig::createMessagePublisherFactory);
    }

}
