package one.tomorrow.transactionaloutbox.reactive.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.relational.core.mapping.Table;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

@Table("outbox_kafka")
@Immutable
@AllArgsConstructor
@Builder(toBuilder = true)
@Getter
@ToString(exclude = "value")
public class OutboxRecord {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Id
    private final Long id;
    private final Instant created;
    private final Instant processed;
    private final String topic;
    private final String key;
    private final byte[] value;
    private final Json headers;

    public Map<String, String> getHeadersAsMap() {
        byte[] data = headers == null ? null : headers.asArray();
        if (data == null || data.length == 0)
            return Collections.emptyMap();

        try {
            return OBJECT_MAPPER.readValue(data, new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Json toJson(Map<String, String> headers) {
        if (headers == null)
            return null;
        try {
            return Json.of(OBJECT_MAPPER.writeValueAsBytes(headers));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to convert to json: " + headers, e);
        }
    }

}
