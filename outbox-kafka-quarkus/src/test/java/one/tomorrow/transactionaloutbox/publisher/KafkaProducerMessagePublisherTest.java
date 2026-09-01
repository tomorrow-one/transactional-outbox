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

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaProducerMessagePublisherTest {

    private final KafkaProducer<String, byte[]> kafkaProducer = mock();
    private final KafkaProducerMessagePublisher publisher = new KafkaProducerMessagePublisher(kafkaProducer);

    @Test
    void publishSuccessfullySendsMessage() {
        CompletableFuture<ProducerRecord<String, byte[]>> future = CompletableFuture.completedFuture(null);
        when(kafkaProducer.send(any())).thenAnswer(inv -> future);

        Future<?> result = publisher.publish(
                1L, "test-topic", "key", "payload".getBytes(), Map.of("h1", "v1".getBytes())
        );

        assertDoesNotThrow(() -> result.get());
        verify(kafkaProducer).send(argThat((ProducerRecord<String, byte[]> pr) -> {
            assertEquals("test-topic", pr.topic());
            assertEquals("key", pr.key());
            assertEquals("payload", new String(pr.value()));
            assertEquals(1, pr.headers().toArray().length);
            assertEquals("v1", new String(pr.headers().lastHeader("h1").value()));
            return true;
        }));
    }

    @Test
    void publishFailsWhenKafkaProducerThrowsException() {
        CompletableFuture<ProducerRecord<String, byte[]>> future =
                CompletableFuture.failedFuture(new RuntimeException("simulated error"));
        when(kafkaProducer.send(any())).thenAnswer(inv -> future);

        CompletableFuture<?> result = (CompletableFuture<?>) publisher.publish(
                1L, "test-topic", "key", "payload".getBytes(), Map.of("header1", "value1".getBytes())
        );

        assertTrue(result.isCompletedExceptionally());
        assertThrows(RuntimeException.class, result::join);
    }

    @Test
    void closeClosesKafkaProducer() {
        publisher.close();

        verify(kafkaProducer).close();
    }
}
