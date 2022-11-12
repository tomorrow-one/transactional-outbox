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

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import one.tomorrow.kafka.messages.DeserializerMessages.InvalidMessage;
import one.tomorrow.kafka.messages.DeserializerMessages.UnsupportedMessage;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.StreamSupport.stream;
import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_VALUE_TYPE_NAME;

public class KafkaProtobufDeserializer implements Deserializer<Message> {

    private final Map<String, Class<?>> valueTypes;
    private final boolean throwOnError;

    public KafkaProtobufDeserializer(Iterable<Class<? extends Message>> valueTypes, boolean throwOnError) {
        this.valueTypes = stream(valueTypes.spliterator(), false)
                .map(this::protoFullNameToClass)
                .collect(toMap(Entry::getKey, Entry::getValue));
        this.throwOnError = throwOnError;
    }

    public KafkaProtobufDeserializer(Map<String, Class<?>> valueTypes, boolean throwOnError) {
        this.valueTypes = valueTypes;
        this.throwOnError = throwOnError;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) { }

    @Override
    public Message deserialize(String topic, byte[] data) {
        return getInvalidMessageOrThrow("Headers not available", data);
    }

    @Override
    public Message deserialize(String topic, Headers headers, byte[] data) {
        String headerValueType = Optional
                .ofNullable(headers.lastHeader(HEADERS_VALUE_TYPE_NAME))
                .map(h -> new String(h.value()))
                .orElse(null);

        if (headerValueType == null) {
            return getInvalidMessageOrThrow("No '" + HEADERS_VALUE_TYPE_NAME + "' header present", data);
        }

        Class<?> clazz = valueTypes.get(headerValueType);
        if (clazz == null) {
            if (throwOnError)
                throw new IllegalArgumentException("Unknown type " + headerValueType);

            return UnsupportedMessage.newBuilder()
                    .setData(ByteString.copyFrom(data))
                    .build();
        }

        return getMessage(headers, data, clazz);
    }

    private Message getMessage(Headers headers, byte[] data, Class<?> clazz) {
        try {
            Method m;
            if (Message.class.isAssignableFrom(clazz)) {
                m = ReflectionUtils.findMethod(clazz, "parseFrom", byte[].class);
            } else {
                return getInvalidMessageOrThrow("Not a supported class: " + clazz, data);
            }
            if (null == m) {
                return getInvalidMessageOrThrow("Class " + clazz + " does not provide 'parseFrom(byte[])'", data);
            }

            return (Message) m.invoke(null, (Object) data);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            return getInvalidMessageOrThrow("Failed to parse data with class " + clazz, data, e);
        }
    }

    private InvalidMessage getInvalidMessageOrThrow(String error, byte[] data) {
        return getInvalidMessageOrThrow(error, data, null);
    }

    private InvalidMessage getInvalidMessageOrThrow(String error, byte[] data, Exception cause) {
        if (throwOnError)
            throw new IllegalArgumentException(error, cause);

        return InvalidMessage.newBuilder()
                .setError(error)
                .setData(ByteString.copyFrom(data))
                .build();
    }

    @Override
    public void close() {
    }

    private SimpleEntry<String, Class<? extends Message>> protoFullNameToClass(Class<? extends Message> valueTypeClass) {
        try {
            Method getDescriptor = valueTypeClass.getMethod("getDescriptor");
            String protoFullName = ((Descriptor) getDescriptor.invoke(null)).getFullName();
            return new SimpleEntry<>(protoFullName, valueTypeClass);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

}