/**
 * Copyright 2024 Tomorrow GmbH @ https://tomorrow.one
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package one.tomorrow.transactionaloutbox.reactive.service;

import org.junit.Test;

import java.util.Map;

import static one.tomorrow.transactionaloutbox.commons.CommonKafkaTestSupport.producerProps;
import static org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.common.config.SaslConfigs.SASL_JAAS_CONFIG;
import static org.junit.Assert.assertEquals;

@SuppressWarnings("unchecked")
public class DefaultKafkaProducerFactoryTest {

    @Test
    public void should_buildLoggableProducerWithoutSensitiveContent() {
        // given
        Map<String, Object> producerProps = producerProps("bootstrapServers");
        String saslJaasConfig = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"abc-backend-user\" password=\"xyz\";";
        producerProps.put(SASL_JAAS_CONFIG, saslJaasConfig);

        // when
        Map<String, Object> loggableProducerProps = DefaultKafkaProducerFactory.loggableProducerProps(producerProps);

        // then
        assertEquals("[hidden]", loggableProducerProps.get(SASL_JAAS_CONFIG));
        assertEquals("bootstrapServers", loggableProducerProps.get(BOOTSTRAP_SERVERS_CONFIG));

        // make sure we don't change the original values by side effect
        assertEquals(saslJaasConfig, producerProps.get(SASL_JAAS_CONFIG));
    }
}
