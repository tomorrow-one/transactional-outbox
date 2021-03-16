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

            kafka = (ProxiedKafkaContainer) new ProxiedKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:5.4.3"))
                    .withExposedPorts(exposedKafkaPort)
                    .withNetwork(network);

            toxiproxy = new ToxiproxyContainer(DockerImageName.parse("shopify/toxiproxy:2.1.0"))
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