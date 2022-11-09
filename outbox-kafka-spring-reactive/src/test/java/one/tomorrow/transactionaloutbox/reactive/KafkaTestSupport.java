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

import com.google.protobuf.Message;
import one.tomorrow.kafka.core.KafkaProtobufSerializer;
import one.tomorrow.kafka.core.Longs;
import one.tomorrow.transactionaloutbox.reactive.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.reactive.service.DefaultKafkaProducerFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static one.tomorrow.kafka.core.KafkaHeaders.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.kafka.core.KafkaHeaders.HEADERS_SOURCE_NAME;
import static one.tomorrow.kafka.core.Longs.toLong;
import static one.tomorrow.transactionaloutbox.reactive.ProxiedKafkaContainer.bootstrapServers;
import static org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public interface KafkaTestSupport {

    static Map<String, Object> consumerProps(String bootstrapServers, String group, boolean autoCommit) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommit);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "10");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "60000");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    static Consumer<String, byte[]> createConsumer(String bootstrapServers) {
        Map<String, Object> consumerProps = consumerProps(bootstrapServers, "testGroup", true);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        DefaultKafkaConsumerFactory<String, byte[]> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
        // use unique groupId, so that a new consumer does not get into conflicts with some previous one, which might not yet be fully shutdown
        return cf.createConsumer("testConsumer-" + System.currentTimeMillis(), "someClientIdSuffix");
    }

    static Map<String, Object> producerProps(String bootstrapServers) {
        return new HashMap<>(Map.of(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    static DefaultKafkaProducerFactory producerFactory() {
        return producerFactory(producerProps(bootstrapServers));
    }

    static DefaultKafkaProducerFactory producerFactory(Map<String, Object> producerProps) {
        return new DefaultKafkaProducerFactory(producerProps);
    }

    static KafkaProducer<String, Message> createTopicAndProducer(String bootstrapServers, String ... topics) {
        createTopic(bootstrapServers, topics);
        return createProducer(bootstrapServers);
    }

    static KafkaProducer<String, Message> createProducer(String bootstrapServers) {
        Map<String, Object> props = producerProps(bootstrapServers);
        props.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
        return new KafkaProducer<>(props);
    }

    static void createTopic(String bootstrapServers, String ... topics) {
        Map<String, Object> props = producerProps(bootstrapServers);
        try (AdminClient client = AdminClient.create(props)) {
            List<NewTopic> newTopics = Arrays.stream(topics)
                    .map(topic -> new NewTopic(topic, 1, (short) 1))
                    .collect(toList());
            try {
                client.createTopics(newTopics).all().get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static void assertConsumedRecord(OutboxRecord outboxRecord, String sourceHeaderValue, ConsumerRecord<String, byte[]> kafkaRecord) {
        assertEquals(outboxRecord.getId().longValue(), toLong(kafkaRecord.headers().lastHeader(HEADERS_SEQUENCE_NAME).value()));
        assertArrayEquals(sourceHeaderValue.getBytes(), kafkaRecord.headers().lastHeader(HEADERS_SOURCE_NAME).value());
        outboxRecord.getHeadersAsMap().forEach((key, value) ->
                assertArrayEquals(value.getBytes(), kafkaRecord.headers().lastHeader(key).value())
        );
        assertEquals(outboxRecord.getKey(), kafkaRecord.key());
        assertArrayEquals(outboxRecord.getValue(), kafkaRecord.value());
    }

    default ConsumerRecords<String, byte[]> getAndCommitRecords() {
        return getAndCommitRecords(-1);
    }

    default ConsumerRecords<String, byte[]> getAndCommitRecords(int minRecords) {
        ConsumerRecords<String, byte[]> records = KafkaTestUtils.getRecords(consumer(), 10_000, minRecords);
        consumer().commitSync();
        return records;
    }

    Consumer<String, byte[]> consumer();

}