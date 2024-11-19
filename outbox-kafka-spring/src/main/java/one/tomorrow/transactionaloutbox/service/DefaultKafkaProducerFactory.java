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
package one.tomorrow.transactionaloutbox.service;

import one.tomorrow.transactionaloutbox.service.OutboxProcessor.KafkaProducerFactory;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.apache.kafka.clients.producer.ProducerConfig.*;
import static org.apache.kafka.clients.producer.ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG;

public class DefaultKafkaProducerFactory implements KafkaProducerFactory {

    private static final Logger logger = LoggerFactory.getLogger(DefaultKafkaProducerFactory.class);

    private final HashMap<String, Object> producerProps;

    public DefaultKafkaProducerFactory(Map<String, Object> producerProps) {
        HashMap<String, Object> props = new HashMap<>(producerProps);
        // Settings for guaranteed ordering (via enable.idempotence) and dealing with broker failures.
        // Note that with `enable.idempotence = true` ordering of messages is also checked by the broker.
        if (Boolean.FALSE.equals(props.get(ENABLE_IDEMPOTENCE_CONFIG)))
            logger.warn(ENABLE_IDEMPOTENCE_CONFIG + " is set to 'false' - this might lead to out-of-order messages.");

        setIfNotSet(props, ENABLE_IDEMPOTENCE_CONFIG, true);

        // serializer settings
        props.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        this.producerProps = props;
    }

    private static void setIfNotSet(Map<String, Object> props, String prop, Object value) {
        if (!props.containsKey(prop)) props.put(prop, value);
    }

    @Override
    public KafkaProducer<String, byte[]> createKafkaProducer() {
        return new KafkaProducer<>(producerProps);
    }

    @Override
    public String toString() {
        return "DefaultKafkaProducerFactory{producerProps=" + loggableProducerProps(producerProps) + '}';
    }

    static Map<String, Object> loggableProducerProps(Map<String, Object> producerProps) {
        Map<String, Object> maskedProducerProps = new HashMap<>(producerProps);
        maskedProducerProps.replaceAll((key, value) -> key.equalsIgnoreCase("sasl.jaas.config") ? "[hidden]" : value);
        return maskedProducerProps;
    }

}
