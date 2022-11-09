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
package one.tomorrow.transactionaloutbox.reactive;

import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

public class ProxiedKafkaContainer extends KafkaContainer {

    private static ProxiedKafkaContainer kafka;
    private static ToxiproxyContainer toxiproxy;
    public static ToxiproxyContainer.ContainerProxy kafkaProxy;
    public static String bootstrapServers;

    public ProxiedKafkaContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
    }

    public static ProxiedKafkaContainer startProxiedKafka() {
        if (kafka == null) {
            int exposedKafkaPort = KAFKA_PORT;

            Network network = Network.newNetwork();

            kafka = (ProxiedKafkaContainer) new ProxiedKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.1.2"))
                    .withExposedPorts(exposedKafkaPort)
                    .withNetwork(network);

            toxiproxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
                    .withNetwork(network);

            toxiproxy.start();
            kafkaProxy = toxiproxy.getProxy(kafka, exposedKafkaPort);
            bootstrapServers = "PLAINTEXT://" + kafkaProxy.getContainerIpAddress() + ":" + kafkaProxy.getProxyPort();

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

    /** Kafka advertises its connection to connected clients, therefore we must override this */
    public String getBootstrapServers() {
        return bootstrapServers;
    }

}