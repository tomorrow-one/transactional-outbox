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
package one.tomorrow.transactionaloutbox;

import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SOURCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.Longs.toLong;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class KafkaTestUtils {

    private static final Logger logger = LoggerFactory.getLogger(KafkaTestUtils.class);

    public static void createTopic(String bootstrapServers, String ... topics) {
        Map<String, Object> props = producerProps(bootstrapServers);
        try (AdminClient client = AdminClient.create(props)) {
            List<NewTopic> newTopics = Arrays.stream(topics)
                    .map(topic -> new NewTopic(topic, 1, (short) 1))
                    .toList();
            try {
                client.createTopics(newTopics).all().get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static Map<String, Object> producerProps() {
        return producerProps(bootstrapServers());
    }

    public static String bootstrapServers() {
        return ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String.class);
    }

    /**
     * Set up test properties for an {@code <Integer, String>} producer.
     * @param brokers the bootstrapServers property.
     * @return the properties.
     * @since 2.3.5
     */
    public static Map<String, Object> producerProps(String brokers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, "33554432");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, IntegerSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return props;
    }

    public static KafkaConsumer<String, byte[]> setupConsumer(
            String groupId,
            boolean autoCommit,
            String ... topicsToSubscribe) {
        return setupConsumer(groupId,
                autoCommit,
                StringDeserializer.class,
                ByteArrayDeserializer.class,
                topicsToSubscribe);
    }

    public static <K, V> KafkaConsumer<K, V> setupConsumer(
            String groupId,
            boolean autoCommit,
            Class<? extends Deserializer<K>> keyDeserializer,
            Class<? extends Deserializer<V>> valueDeserializer,
            String ... topicsToSubscribe) {
        // use unique groupId, so that a new consumer does not get into conflicts with some previous one,
        // which might not yet be fully shutdown
        Map<String, Object> consumerProps = consumerProps(groupId, String.valueOf(autoCommit));
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDeserializer);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializer);
        consumerProps.put(ConsumerConfig.CLIENT_ID_CONFIG, "testConsumer-" + System.currentTimeMillis() + "-clientId");
        KafkaConsumer<K, V> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Arrays.asList(topicsToSubscribe));
        return consumer;
    }

    public static Map<String, Object> consumerProps(String group, String autoCommit) {
        return consumerProps(bootstrapServers(), group, autoCommit);
    }

    /**
     * Set up test properties for an {@code <Integer, String>} consumer.
     * @param brokers the bootstrapServers property.
     * @param group the group id.
     * @param autoCommit the auto commit.
     * @return the properties.
     */
    public static Map<String, Object> consumerProps(String brokers, String group, String autoCommit) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommit);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "10");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "60000");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    /**
     * Poll the consumer for records.
     * @param consumer the consumer.
     * @param timeout max time in milliseconds to wait for records; forwarded to {@link Consumer#poll(Duration)}.
     * @param <K> the key type.
     * @param <V> the value type.
     * @return the records.
     * @throws IllegalStateException if the poll returns null (since 2.3.4).
     * @since 2.9.3
     */
    public static <K, V> ConsumerRecords<K, V> getRecords(Consumer<K, V> consumer, Duration timeout) {
        return getRecords(consumer, timeout, -1);
    }

    /**
     * Poll the consumer for records.
     * @param consumer the consumer.
     * @param timeout max time in milliseconds to wait for records; forwarded to {@link Consumer#poll(Duration)}.
     * @param <K> the key type.
     * @param <V> the value type.
     * @param minRecords wait until the timeout or at least this number of records are received.
     * @return the records.
     * @throws IllegalStateException if the poll returns null.
     * @since 2.9.3
     */
    public static <K, V> ConsumerRecords<K, V> getRecords(Consumer<K, V> consumer, Duration timeout, int minRecords) {
        logger.debug("Polling...");
        Map<TopicPartition, List<ConsumerRecord<K, V>>> records = new HashMap<>();
        long remaining = timeout.toMillis();
        int count = 0;
        do {
            long t1 = System.currentTimeMillis();
            ConsumerRecords<K, V> received = consumer.poll(Duration.ofMillis(remaining));
            logger.debug("Received: {}", received != null ? received.count() : null);
            if (received == null) {
                throw new IllegalStateException("null received from consumer.poll()");
            }
            if (minRecords < 0) {
                return received;
            }
            else {
                count += received.count();
                received.partitions().forEach(tp -> {
                    List<ConsumerRecord<K, V>> recs = records.computeIfAbsent(tp, part -> new ArrayList<>());
                    recs.addAll(received.records(tp));
                });
                remaining -= System.currentTimeMillis() - t1;
            }
        }
        while (count < minRecords && remaining > 0);
        return new ConsumerRecords<>(records, Map.of());
    }

    public static void assertConsumedRecord(OutboxRecord outboxRecord,
                                            String sourceHeaderValue,
                                            ConsumerRecord<String, byte[]> kafkaRecord) {
        assertEquals(
                outboxRecord.getId().longValue(),
                toLong(kafkaRecord.headers().lastHeader(HEADERS_SEQUENCE_NAME).value()),
                "OutboxRecord id and " + HEADERS_SEQUENCE_NAME + " headers do not match"
        );
        assertEquals(sourceHeaderValue, new String(kafkaRecord.headers().lastHeader(HEADERS_SOURCE_NAME).value()));
        outboxRecord.getHeaders().forEach((key, value) ->
                assertEquals(new String(value.getBytes()), new String(kafkaRecord.headers().lastHeader(key).value()))
        );
        assertEquals(outboxRecord.getKey(), kafkaRecord.key());
        assertArrayEquals(outboxRecord.getValue(), kafkaRecord.value());
    }

    public static void assertConsumedRecord(OutboxRecord outboxRecord,
                                            String headerKey,
                                            String sourceHeaderValue,
                                            ConsumerRecord<String, byte[]> kafkaRecord) {
        assertEquals(outboxRecord.getKey(), kafkaRecord.key());
        assertArrayEquals(outboxRecord.getValue(), kafkaRecord.value());
        assertArrayEquals(outboxRecord.getHeaders().get(headerKey).getBytes(), kafkaRecord.headers().lastHeader(headerKey).value());
        assertEquals(outboxRecord.getId().longValue(), toLong(kafkaRecord.headers().lastHeader(HEADERS_SEQUENCE_NAME).value()));
        assertArrayEquals(sourceHeaderValue.getBytes(), kafkaRecord.headers().lastHeader(HEADERS_SOURCE_NAME).value());
    }

}
