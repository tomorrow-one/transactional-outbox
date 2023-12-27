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
package one.tomorrow.transactionaloutbox.commons;

import com.google.protobuf.Message;
import one.tomorrow.transactionaloutbox.commons.KafkaProtobufSerializer;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SEQUENCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_SOURCE_NAME;
import static one.tomorrow.transactionaloutbox.commons.Longs.toLong;
import static one.tomorrow.transactionaloutbox.commons.ProxiedKafkaContainer.bootstrapServers;
import static org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public interface CommonKafkaTestSupport<T> {

    static Consumer<String, byte[]> createConsumer(String bootstrapServers) {
        return createConsumer(bootstrapServers, ByteArrayDeserializer.class);
    }

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

    static <T> Consumer<String, T> createConsumer(String bootstrapServers, Class<? extends Deserializer<T>> deserializerClass) {
        Map<String, Object> consumerProps = consumerProps(bootstrapServers, "testGroup", true);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializerClass);
        DefaultKafkaConsumerFactory<String, T> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
        // use unique groupId, so that a new consumer does not get into conflicts with some previous one, which might not yet be fully shutdown
        return cf.createConsumer("testConsumer-" + System.currentTimeMillis(), "someClientIdSuffix");
    }

    static Map<String, Object> producerProps() {
        return producerProps(bootstrapServers);
    }

    static Map<String, Object> producerProps(String bootstrapServers) {
        return new HashMap<>(Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    static KafkaProducer<String, Message> createTopicAndProducer(String bootstrapServers, String ... topics) {
        createTopic(bootstrapServers, topics);
        return createProducer(bootstrapServers);
    }

    static KafkaProducer<String, Message> createProducer(String bootstrapServers) {
        Map<String, Object> props = producerProps(bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaProtobufSerializer.class);
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

    default ConsumerRecords<String, T> getAndCommitRecords() {
        return getAndCommitRecords(-1);
    }

    default ConsumerRecords<String, T> getAndCommitRecords(int minRecords) {
        ConsumerRecords<String, T> records = KafkaTestUtils.getRecords(consumer(), Duration.ofSeconds(10), minRecords);
        consumer().commitSync();
        return records;
    }

    Consumer<String, T> consumer();

}
