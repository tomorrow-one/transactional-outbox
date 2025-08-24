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
package one.tomorrow.transactionaloutbox.quarkus.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.hibernate.orm.deployment.spi.AdditionalJpaModelBuildItem;
import one.tomorrow.transactionaloutbox.config.TransactionalOutboxConfig;
import one.tomorrow.transactionaloutbox.model.OutboxLock;
import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import one.tomorrow.transactionaloutbox.repository.OutboxLockRepository;
import one.tomorrow.transactionaloutbox.repository.OutboxRepository;
import one.tomorrow.transactionaloutbox.service.DefaultKafkaProducerFactory;
import one.tomorrow.transactionaloutbox.service.OutboxLockService;
import one.tomorrow.transactionaloutbox.service.OutboxProcessor;
import one.tomorrow.transactionaloutbox.service.OutboxService;

import java.util.List;

class TransactionalOutboxExtensionProcessor {

    private static final String FEATURE = "transactional-outbox";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem outboxBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(
                        OutboxLockRepository.class,
                        OutboxRepository.class,
                        OutboxLockService.class,
                        TransactionalOutboxConfig.class,
                        TransactionalOutboxConfig.CleanupConfig.class,
                        OutboxService.class,
                        OutboxProcessor.class,
                        OutboxProcessor.KafkaProducerFactory.class,
                        DefaultKafkaProducerFactory.class
                )
                .setUnremovable()
                .build();
    }

    @BuildStep
    List<AdditionalJpaModelBuildItem> jpaModels() {
        return List.of(
                new AdditionalJpaModelBuildItem(OutboxLock.class.getName()),
                new AdditionalJpaModelBuildItem(OutboxRecord.class.getName())
        );
    }

}
