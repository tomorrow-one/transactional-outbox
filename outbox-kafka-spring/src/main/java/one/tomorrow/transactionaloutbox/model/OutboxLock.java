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
package one.tomorrow.transactionaloutbox.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

@Entity
@Table(name="outbox_kafka_lock")
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
	@Column(name="id")
	private String id = OUTBOX_LOCK_ID;

	@Column(name="owner_id")
	private String ownerId;

	@Column(name="valid_until")
	private Instant validUntil;

}
