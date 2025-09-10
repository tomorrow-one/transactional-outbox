/**
 * Copyright 2023 Tomorrow GmbH @ https://tomorrow.one
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
package one.tomorrow.transactionaloutbox;

import eu.rekawek.toxiproxy.Proxy;
import org.jetbrains.annotations.NotNull;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import static one.tomorrow.transactionaloutbox.ProxiedContainerPorts.findPort;
import static one.tomorrow.transactionaloutbox.ProxiedContainerSupport.createProxy;

public class ProxiedPostgreSQLContainer extends PostgreSQLContainer<ProxiedPostgreSQLContainer> implements ProxiedContainerSupport {

    private static ProxiedPostgreSQLContainer postgres;
    public static ToxiproxyContainer toxiproxy;
    public static Proxy postgresProxy;
    private static String jdbcUrl;

    public ProxiedPostgreSQLContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
    }

    public static ProxiedPostgreSQLContainer startProxiedPostgres() {
        if (postgres == null) {
            int exposedPostgresPort = POSTGRESQL_PORT;

            Network network = Network.newNetwork();

            postgres = new ProxiedPostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withExposedPorts(exposedPostgresPort)
                    .withNetwork(network)
                    .withNetworkAliases("postgres");

            toxiproxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
                    .withNetwork(network);
            toxiproxy.start();

            postgresProxy = createProxy("postgres", toxiproxy, exposedPostgresPort);

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

    @Override
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    @Override
    public Proxy getProxy() {
        return postgresProxy;
    }

}
