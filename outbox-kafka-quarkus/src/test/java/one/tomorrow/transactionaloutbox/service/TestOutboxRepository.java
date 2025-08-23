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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import org.eclipse.microprofile.faulttolerance.Retry;

import static java.time.temporal.ChronoUnit.MILLIS;

/**
 * Helper class, which provides the transactional boundary for {@link OutboxRepository#persist(OutboxRecord)}
 */
@ApplicationScoped
public class TestOutboxRepository {

    @Inject
	OutboxRepository repository;

	public TestOutboxRepository(OutboxRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public void persist(OutboxRecord outboxRecord) {
		repository.persist(outboxRecord);
	}

    @Retry(maxRetries = 10, delay = 500, delayUnit = MILLIS)
    public void persistWithRetry(OutboxRecord outboxRecord) {
        persist(outboxRecord);
    }

}
