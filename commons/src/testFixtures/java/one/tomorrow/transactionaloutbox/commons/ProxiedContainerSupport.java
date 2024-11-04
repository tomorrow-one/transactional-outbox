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
package one.tomorrow.transactionaloutbox.commons;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.SneakyThrows;
import org.testcontainers.containers.ToxiproxyContainer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static one.tomorrow.transactionaloutbox.commons.ProxiedContainerPorts.findPort;

public interface ProxiedContainerSupport {

    String CUT_CONNECTION_DOWNSTREAM = "CUT_CONNECTION_DOWNSTREAM";
    String CUT_CONNECTION_UPSTREAM = "CUT_CONNECTION_UPSTREAM";

    AtomicBoolean isCurrentlyCut = new AtomicBoolean(false);

    Proxy getProxy();

    /**
     * Cuts the connection by setting bandwidth in both directions to zero.
     * @param shouldCutConnection true if the connection should be cut, or false if it should be re-enabled
     */
    @SneakyThrows
    default void setConnectionCut(boolean shouldCutConnection) {
        synchronized (isCurrentlyCut) {
            if (shouldCutConnection != isCurrentlyCut.get()) {
                try {
                    if (shouldCutConnection) {
                        getProxy().toxics().bandwidth(CUT_CONNECTION_DOWNSTREAM, ToxicDirection.DOWNSTREAM, 0);
                        getProxy().toxics().bandwidth(CUT_CONNECTION_UPSTREAM, ToxicDirection.UPSTREAM, 0);
                        isCurrentlyCut.set(true);
                    } else {
                        removeToxicIfPresent(CUT_CONNECTION_DOWNSTREAM);
                        removeToxicIfPresent(CUT_CONNECTION_UPSTREAM);
                        isCurrentlyCut.set(false);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Could not control proxy", e);
                }
            }
        }
    }

    private void removeToxicIfPresent(String name) throws IOException {
        Toxic toxic = getToxic(name);
        if (toxic != null) {
            toxic.remove();
        }
    }

    private Toxic getToxic(String name) {
        try {
            return getProxy().toxics().get(name);
        } catch (IOException e) {
            if (e.getMessage().contains("[404]")) {
                return null;
            }
            throw new RuntimeException("Could not get toxic", e);
        }
    }

    static Proxy createProxy(String service, ToxiproxyContainer toxiproxy, int exposedPort) {
        final ToxiproxyClient toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        Proxy proxy;
        try {
            proxy = toxiproxyClient.createProxy(
                    service,
                    "0.0.0.0:" + findPort(service),
                    service + ":" + exposedPort
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return proxy;
    }

}
