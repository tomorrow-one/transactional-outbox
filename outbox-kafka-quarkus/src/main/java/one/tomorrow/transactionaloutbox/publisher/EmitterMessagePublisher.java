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

import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.smallrye.reactive.messaging.kafka.OutgoingKafkaRecord;
import jakarta.annotation.Nonnull;
import lombok.AllArgsConstructor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * A {@link MessagePublisher} that is based on quarkus Emitter abstraction.
 * This allows e.g. in tests to replace the Emitter with an InMemoryEmitter,
 * if a real Kafka shall not be used in the test.
 */
@AllArgsConstructor
public class EmitterMessagePublisher implements MessagePublisher {

    /**
     * Resolves a {@link MutinyEmitter} to Kafka topics.
     * The implementation would statically inject emitters with named channels
     * so that it can resolve an emitter to a given Kafka topic.
     *
     * E.g. for order events and a configuration
     * <p>
     *   <code>mp.messaging.outgoing.orderEvents.topic=orders</code>
     * </p>
     * The implementation would inject
     * <p>
     *  <code>@Channel("orderEvents") MutinyEmitter&lt;byte[]&gt; orderEventsEmitter;</code>
     * </p>
     * so that for a given topic "<code>orders</code>" it could resolve the <code>orderEventsEmitter</code>.
     */
    @FunctionalInterface
    public interface EmitterResolver {
        MutinyEmitter<byte[]> resolveBy(String topic);
    }

    private final EmitterResolver emitterResolver;

    @Override
    public Future<?> publish(
            Long id,
            String topic,
            String key,
            byte[] payload,
            @Nonnull Map<String, byte[]> headers
    ) {
        MutinyEmitter<byte[]> emitter = emitterResolver.resolveBy(topic);
        if (emitter == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No emitter found for topic " + topic)
            );
        }

        OutgoingKafkaRecord<String, byte[]> kafkaRecord = KafkaRecord.of(key, payload);
        for (Map.Entry<String, byte[]> header : headers.entrySet()) {
            kafkaRecord = kafkaRecord.withHeader(header.getKey(), header.getValue());
        }

        return emitter
                .sendMessage(kafkaRecord)
                .subscribeAsCompletionStage();
    }

    @Override
    public void close() {
        // noop
    }

}
