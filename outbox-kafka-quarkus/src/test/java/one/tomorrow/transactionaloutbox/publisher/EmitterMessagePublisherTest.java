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

import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.OutgoingKafkaRecord;
import one.tomorrow.transactionaloutbox.publisher.EmitterMessagePublisher.EmitterResolver;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class EmitterMessagePublisherTest {

    private final EmitterResolver emitterResolver = mock();
    private final MutinyEmitter<byte[]> emitter = mock();
    private final EmitterMessagePublisher publisher = new EmitterMessagePublisher(emitterResolver);

    @Test
    void publishSuccessfullyPublishesMessage() {
        when(emitterResolver.resolveBy("test-topic")).thenReturn(emitter);
        when(emitter.sendMessage(any(OutgoingKafkaRecord.class)))
                .thenReturn(Uni.createFrom().nullItem());

        CompletableFuture<?> result = (CompletableFuture<?>) publisher.publish(
                1L, "test-topic", "key", "payload".getBytes(), Map.of("k", "v".getBytes())
        );

        assertDoesNotThrow(result::join);
        verify(emitter).sendMessage(argThat((OutgoingKafkaRecord<String, byte[]> kafkaRecord) -> {
            assertEquals("key", kafkaRecord.getKey());
            assertEquals("payload", new String(kafkaRecord.getPayload()));
            assertEquals(1, kafkaRecord.getHeaders().toArray().length);
            assertEquals("v", new String(kafkaRecord.getHeaders().lastHeader("k").value()));
            return true;
        }));
    }

    @Test
    void publishFailsWhenEmitterNotFound() {
        when(emitterResolver.resolveBy("non-existent-topic")).thenReturn(null);

        CompletableFuture<?> result = (CompletableFuture<?>) publisher.publish(
                1L, "non-existent-topic", "key", "payload".getBytes(), Collections.emptyMap()
        );

        assertTrue(result.isCompletedExceptionally());
        CompletionException ex = assertThrows(CompletionException.class, result::join);
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

}
