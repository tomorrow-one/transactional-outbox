package one.tomorrow.transactionaloutbox.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("outbox_kafka_lock")
@NoArgsConstructor
@Getter
@Setter
public class OutboxLock {

	// the static value that is used to identify the single possible record in this table - i.e. we make
	// use of the uniqueness guarantee of the database to ensure that only a single lock at the same time exists
	public static final String OUTBOX_LOCK_ID = "outboxLock";

	public OutboxLock(String ownerId, Instant validUntil) {
		this.ownerId = ownerId;
		this.validUntil = validUntil;
	}

	@Id
	private String id = OUTBOX_LOCK_ID;

	private String ownerId;

	private Instant validUntil;

}
