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

import com.google.protobuf.MessageLite;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

/**
 * Serializer for Kafka to serialize Protocol Buffers messages
 *
 * @param <T> Protobuf message type
 */
public class KafkaProtobufSerializer<T extends MessageLite> implements Serializer<T> {

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
    }

    @Override
    public byte[] serialize(String topic, T data) {
        return data.toByteArray();
    }

    @Override
    public void close() {
    }

}
