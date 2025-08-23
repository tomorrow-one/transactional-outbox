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
package one.tomorrow.transactionaloutbox.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;

import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "outbox_kafka")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
@ToString(exclude = "value")
public class OutboxRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private Timestamp created;

    @Column(name = "processed")
    private Instant processed;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "key")
    private String key;

    @Column(name = "value", nullable = false)
    private byte[] value;

    @Column(name = "headers")
    @JdbcTypeCode(SqlTypes.JSON)
    @Convert(converter = MapConverter.class)
    private Map<String, String> headers;

    static class MapConverter implements AttributeConverter<Map<String, String>, String> {

        private static final TypeReference<Map<String, String>> TYPE_REFERENCE = new TypeReference<>() {};
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(Map<String, String> value) {
            try {
                return value == null ? null : OBJECT_MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                throw new PersistenceException("Failed to serialize value to JSON: " + value, e);
            }
        }

        @Override
        public Map<String, String> convertToEntityAttribute(String dbData) {
            try {
                return dbData == null ? null : OBJECT_MAPPER.readValue(dbData, TYPE_REFERENCE);
            } catch (Exception e) {
                throw new PersistenceException("Failed to parse JSON string: " + dbData, e);
            }
        }
    }
}
