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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxiedContainerPorts {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxiedContainerPorts.class);

    /** First and last proxied ports from {@link org.testcontainers.containers.ToxiproxyContainer} */
    private static final int FIRST_PROXIED_PORT = 8666;
    private static final int LAST_PROXIED_PORT = 8666 + 31;

    private static final AtomicInteger NEXT_PORT = new AtomicInteger(FIRST_PROXIED_PORT);
    private static final Map<String, Integer> PORT_BY_SERVICE = new HashMap<>();


    public static int findPort(String service) {
        Integer result = PORT_BY_SERVICE.get(service);
        if (result != null)
            return result;

        result = findNextPort(NEXT_PORT.getAndIncrement());
        LOGGER.info("Setting port for {} to {}", service, result);
        PORT_BY_SERVICE.put(service, result);
        return result;
    }

    private static int findNextPort(int port) {
        if (port > LAST_PROXIED_PORT) {
            throw new RuntimeException("Could not find free port for proxied container");
        }
        try (ServerSocket socket = new ServerSocket(port)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            LOGGER.info("Port {} is already in use, trying next port", port);
            return findNextPort(NEXT_PORT.getAndIncrement());
        }
    }

}
