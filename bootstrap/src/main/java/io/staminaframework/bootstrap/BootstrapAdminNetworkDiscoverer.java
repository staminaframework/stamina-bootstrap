/*
 * Copyright (c) 2017 Stamina Framework developers.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.staminaframework.bootstrap;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import org.osgi.service.log.LogService;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This component is responsible for locating a Bootstrap Admin instance
 * in connected networks.
 *
 * @author Stamina Framework developers
 */
class BootstrapAdminNetworkDiscoverer {
    private static final int DISCOVERY_UDP_PORT = 17710;
    private final String bindAddress;
    private final LogService logService;


    public BootstrapAdminNetworkDiscoverer(final LogService logService, final String bindAddress) {
        this.bindAddress = bindAddress == null ? "0.0.0.0" : bindAddress;
        this.logService = logService;
    }

    /**
     * Lookup a Bootstrap Admin instance in connected networks.
     * If there are multiple instances, the first one to respond will be chosen.
     *
     * @param timeout network discovery will give up after this time (milliseconds)
     * @return urls to bootstrap package, empty if none
     * @throws IOException if network discovery failed
     */
    public Set<URL> discover(long timeout) throws IOException {
        if (timeout < 1) {
            throw new IllegalArgumentException("Invalid timeout: " + timeout);
        }

        final AtomicReference<Set<URL>> result = new AtomicReference<>(Collections.emptySet());
        final byte[] payload = new byte[1024];
        final DatagramSocket so =
                new DatagramSocket(new InetSocketAddress(bindAddress, DISCOVERY_UDP_PORT));

        final Runnable probeTask = () -> {
            logService.log(LogService.LOG_DEBUG, "Starting bootstrap network probe");
            try {
                final DatagramPacket pkt = new DatagramPacket(payload, 0, payload.length);
                so.receive(pkt);

                logService.log(LogService.LOG_DEBUG, "Reading response from server: " + pkt.getSocketAddress());

                final String adv = new String(payload, pkt.getOffset(), pkt.getLength(), "UTF-8");
                logService.log(LogService.LOG_DEBUG, "Parsing response: " + adv);

                final JsonObject json = Json.parse(adv).asObject();
                final int version = json.getInt("version", 1);
                if (version > 0) {
                    final JsonValue urlsValue = json.get("bootstrap-package-urls");
                    if (urlsValue != null) {
                        final JsonArray urlsArray = urlsValue.asArray();
                        if (urlsArray == null || urlsArray.isEmpty()) {
                            throw new IOException("No URL set by bootstrap admin instance");
                        }
                        final Set<URL> urls = new HashSet<>(urlsArray.size());
                        for (final JsonValue jsonValue : urlsArray.values()) {
                            urls.add(new URL(jsonValue.asString()));
                        }
                        logService.log(LogService.LOG_DEBUG, "Got URLs from bootstrap admin: " + urls);
                        result.set(urls);
                    }
                }
            } catch (Exception e) {
                if (!so.isClosed()) {
                    logService.log(LogService.LOG_WARNING,
                            "Error while looking for bootstrap package", e);
                }
            }
        };
        final Thread probeThread = new Thread(probeTask, "Stamina Bootstrap Network Probe");
        probeThread.setPriority(Thread.MIN_PRIORITY);
        probeThread.setDaemon(false);
        probeThread.start();
        try {
            probeThread.join(timeout);
            if (probeThread.isAlive()) {
                logService.log(LogService.LOG_DEBUG, "Interrupting bootstrap network probe");
                so.close();
                if (probeThread.isAlive()) {
                    probeThread.interrupt();
                }
            }
        } catch (InterruptedException e) {
        }

        logService.log(LogService.LOG_DEBUG, "Bootstrap network probe stopped");

        return result.get();
    }
}
