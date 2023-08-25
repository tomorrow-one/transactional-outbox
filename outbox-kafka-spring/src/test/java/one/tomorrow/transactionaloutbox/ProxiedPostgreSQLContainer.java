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

import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

public class ProxiedPostgreSQLContainer extends PostgreSQLContainer<ProxiedPostgreSQLContainer> {

    private static ProxiedPostgreSQLContainer postgres;
    public static ToxiproxyContainer toxiproxy;
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

    @Override
    public String getJdbcUrl() {
        return jdbcUrl;
    }

}
