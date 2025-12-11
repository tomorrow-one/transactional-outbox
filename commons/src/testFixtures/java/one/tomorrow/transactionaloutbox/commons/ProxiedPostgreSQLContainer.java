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
import org.jetbrains.annotations.NotNull;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import static one.tomorrow.transactionaloutbox.commons.ProxiedContainerPorts.findPort;
import static one.tomorrow.transactionaloutbox.commons.ProxiedContainerSupport.createProxy;

public class ProxiedPostgreSQLContainer extends PostgreSQLContainer implements ProxiedContainerSupport {

    private static ProxiedPostgreSQLContainer postgres;
    private static ToxiproxyContainer toxiproxy;
    public static Proxy postgresProxy;
    private static String jdbcUrl;

    public ProxiedPostgreSQLContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
    }

    public static ProxiedPostgreSQLContainer startProxiedPostgres() {
        if (postgres == null) {

            Network network = Network.newNetwork();

            postgres = (ProxiedPostgreSQLContainer) new ProxiedPostgreSQLContainer(DockerImageName.parse("postgres:17"))
                    .withNetwork(network)
                    .withNetworkAliases("postgres");

            toxiproxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
                    .withNetwork(network);
            toxiproxy.start();

            postgresProxy = createProxy("postgres", toxiproxy, postgres.getExposedPorts().get(0));

            jdbcUrl = "jdbc:postgresql://" + getHostAndPort() + "/" + postgres.getDatabaseName();

            postgres.start();
        }
        return postgres;
    }

    @NotNull
    private static String getHostAndPort() {
        return toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(findPort("postgres"));
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

        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + getHostAndPort() + "/" + postgres.getDatabaseName());
        registry.add("spring.r2dbc.username", () -> postgres.getUsername());
        registry.add("spring.r2dbc.password", () -> postgres.getPassword());
    }

    @Override
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    @Override
    public Proxy getProxy() {
        return postgresProxy;
    }

}
