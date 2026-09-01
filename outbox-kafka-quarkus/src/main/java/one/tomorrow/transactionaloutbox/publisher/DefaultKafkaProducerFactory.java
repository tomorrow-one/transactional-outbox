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
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.apache.kafka.clients.producer.ProducerConfig.*;

@ApplicationScoped
@DefaultBean
public class DefaultKafkaProducerFactory implements KafkaProducerMessagePublisherFactory.KafkaProducerFactory {

    private static final Logger logger = LoggerFactory.getLogger(DefaultKafkaProducerFactory.class);

    private final HashMap<String, Object> producerProps;

    @Inject
    public DefaultKafkaProducerFactory(@Identifier("default-kafka-broker") Map<String, Object> producerProps) {
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
        props.computeIfAbsent(prop, k -> value);
    }

    @Override
    public KafkaProducer<String, byte[]> createKafkaProducer() {
        return new KafkaProducer<>(producerProps);
    }

    @Override
    public String toString() {
        return "DefaultKafkaProducerFactory{producerProps=" + producerProps + '}';
    }

}
