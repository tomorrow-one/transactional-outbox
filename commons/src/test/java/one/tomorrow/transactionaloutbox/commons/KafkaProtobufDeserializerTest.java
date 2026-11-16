/**
 * Copyright 2026 Tomorrow GmbH @ https://tomorrow.one
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
import one.tomorrow.kafka.messages.DeserializerMessages.UnsupportedMessage;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static one.tomorrow.transactionaloutbox.commons.KafkaHeaders.HEADERS_VALUE_TYPE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KafkaProtobufDeserializerTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void should_DeserializeMessageOfKnownType(boolean throwOnError) {
        KafkaProtobufDeserializer deserializer = new KafkaProtobufDeserializer(List.of(UnsupportedMessage.class), throwOnError);
        UnsupportedMessage message = UnsupportedMessage.newBuilder().setData(ByteString.copyFromUtf8("foo")).build();

        assertEquals(message, deserializer.deserialize("topic", valueTypeHeaders(), message.toByteArray()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void should_ReturnNullForTombstone(boolean throwOnError) {
        KafkaProtobufDeserializer deserializer = new KafkaProtobufDeserializer(List.of(UnsupportedMessage.class), throwOnError);

        assertNull(deserializer.deserialize("topic", valueTypeHeaders(), (byte[]) null));
        assertNull(deserializer.deserialize("topic", new RecordHeaders(), (byte[]) null));
        assertNull(deserializer.deserialize("topic", null));
    }

    private static RecordHeaders valueTypeHeaders() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(HEADERS_VALUE_TYPE_NAME, UnsupportedMessage.getDescriptor().getFullName().getBytes());
        return headers;
    }

}
