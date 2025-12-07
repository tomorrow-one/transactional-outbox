/**
 * Copyright 2022 Tomorrow GmbH @ https://tomorrow.one
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
package one.tomorrow.transactionaloutbox.commons;

import eu.rekawek.toxiproxy.Proxy;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import static one.tomorrow.transactionaloutbox.commons.ProxiedContainerPorts.findPort;
import static one.tomorrow.transactionaloutbox.commons.ProxiedContainerSupport.createProxy;

public class ProxiedKafkaContainer extends ConfluentKafkaContainer implements ProxiedContainerSupport {

    private static ProxiedKafkaContainer kafka;
    private static ToxiproxyContainer toxiproxy;
    public static Proxy kafkaProxy;
    public static String bootstrapServers;

    public ProxiedKafkaContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
    }

    public static ProxiedKafkaContainer startProxiedKafka() {
        if (kafka == null) {

            Network network = Network.newNetwork();

            kafka = (ProxiedKafkaContainer) new ProxiedKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.7"))
                    .withNetwork(network)
                    .withNetworkAliases("kafka");

            toxiproxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
                    .withNetwork(network);
            toxiproxy.start();

            kafkaProxy = createProxy("kafka", toxiproxy, kafka.getExposedPorts().get(0));
            bootstrapServers = toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(findPort("kafka"));

            kafka.start();
        }
        return kafka;
    }

    public static void stopProxiedKafka() {
        if (toxiproxy != null)
            toxiproxy.stop();
        if (kafka != null)
            kafka.stop();
    }

    /**
     * Kafka advertises its connection to connected clients, therefore we must override this
     */
    public String getBootstrapServers() {
        return bootstrapServers;
    }

    @Override
    public Proxy getProxy() {
        return kafkaProxy;
    }
}
