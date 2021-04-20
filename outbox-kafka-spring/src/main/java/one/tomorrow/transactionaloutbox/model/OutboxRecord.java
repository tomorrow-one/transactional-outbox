package one.tomorrow.transactionaloutbox.model;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "outbox_kafka")
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
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

    @Type(type = "jsonb")
    @Column(name = "headers", columnDefinition = "jsonb")
    private Map<String, String> headers;

}
