package one.tomorrow.transactionaloutbox.reactive;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

public class ProxiedPostgreSQLContainer extends PostgreSQLContainer<ProxiedPostgreSQLContainer> {

    private static ProxiedPostgreSQLContainer postgres;
    private static ToxiproxyContainer toxiproxy;
    public static ToxiproxyContainer.ContainerProxy postgresProxy;
    private static String jdbcUrl;

    public ProxiedPostgreSQLContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
    }

    public static ProxiedPostgreSQLContainer startProxiedPostgres() {
        if (postgres == null) {
            int exposedPostgresPort = POSTGRESQL_PORT;

            Network network = Network.newNetwork();

            postgres = new ProxiedPostgreSQLContainer(DockerImageName.parse("postgres:13.7"))
                    .withExposedPorts(exposedPostgresPort)
                    .withNetwork(network);

            toxiproxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
                    .withNetwork(network);

            toxiproxy.start();
            postgresProxy = toxiproxy.getProxy(postgres, exposedPostgresPort);

            jdbcUrl = "jdbc:postgresql://" + postgresProxy.getContainerIpAddress() +
                    ":" + postgresProxy.getProxyPort() + "/" + postgres.getDatabaseName();

            postgres.start();
        }
        return postgres;
    }

    public static void stopProxiedPostgres() {
        if (toxiproxy != null)
            toxiproxy.stop();
        if (postgres != null)
            postgres.stop();
    }

    public static void setConnectionProperties(DynamicPropertyRegistry registry) {
        if (postgres == null)
            startProxiedPostgres();

        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + postgresProxy.getContainerIpAddress() +
                ":" + postgresProxy.getProxyPort() + "/" + postgres.getDatabaseName());
        registry.add("spring.r2dbc.username", () -> postgres.getUsername());
        registry.add("spring.r2dbc.password", () -> postgres.getPassword());
    }

    @Override
    public String getJdbcUrl() {
        return jdbcUrl;
    }

}