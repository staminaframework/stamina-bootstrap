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

package io.staminaframework.bootstrap.admin.internal;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.log.LogService;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.util.HashSet;
import java.util.Set;

/**
 * This component will periodically publish an UDP broadcast packet,
 * advertising about the availability of a bootstrap package.
 *
 * @author Stamina Framework developers
 */
@Component(configurationPid = "io.staminaframework.bootstrap.admin")
public class BootstrapAdminNetworkAdvertiser {
    private static final int DISCOVERY_UDP_PORT = 17710;
    @Reference
    private LogService logService;
    @Reference
    private BootstrapPackage bootstrapPackage;
    private final Set<InetAddress> broadcastAddresses = new HashSet<>(1);
    private Thread worker;
    private volatile boolean running;

    /**
     * Component configuration.
     */
    @interface Config {
        /**
         * Network address where an UDP packet is periodically broadcasted,
         * advertising about the availability of the bootstrap package.
         */
        String bindAddress() default "0.0.0.0";
    }

    @Activate
    void activate(Config config) throws IOException {
        logService.log(LogService.LOG_INFO,
                "Bootstrap package can be downloaded from these endpoints: "
                        + bootstrapPackage.endpoints());

        if ("0.0.0.0".equals(config.bindAddress())) {
            broadcastAddresses.add(InetAddress.getByName("255.255.255.255"));
        } else {
            final NetworkInterface netItf =
                    NetworkInterface.getByInetAddress(InetAddress.getByName(config.bindAddress()));
            if (netItf == null) {
                throw new IllegalArgumentException("No network interface found for bind address: " + config.bindAddress());
            }
            for (InterfaceAddress netItfAddr : netItf.getInterfaceAddresses()) {
                final InetAddress broadcastAddr = netItfAddr.getBroadcast();
                if (broadcastAddr != null) {
                    broadcastAddresses.add(broadcastAddr);
                }
            }
            if (broadcastAddresses.isEmpty()) {
                broadcastAddresses.add(InetAddress.getByName("255.255.255.255"));
            }
        }

        start();
    }

    @Deactivate
    void deactivate() {
        stop();
        broadcastAddresses.clear();
    }

    public void start() {
        final JsonObject json = Json.object();
        json.add("version", 1);
        json.add("bootstrap-package-urls",
                Json.array(bootstrapPackage.endpoints().toArray(new String[0])));

        final String adv = json.toString();
        final byte[] payload;
        try {
            payload = adv.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Unexpected error", e);
        }

        final Runnable task = () -> {
            running = true;
            logService.log(LogService.LOG_INFO, "Starting bootstrap network advertiser");
            while (running) {
                try (final DatagramSocket so = new DatagramSocket()) {
                    for (final InetAddress addr : broadcastAddresses) {
                        sendUdp(so, addr, payload);
                    }
                } catch (Exception e) {
                    logService.log(LogService.LOG_WARNING,
                            "Error while publishing bootstrap advert", e);
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    running = false;
                }
            }
            logService.log(LogService.LOG_INFO, "Bootstrap network advertiser stopped");
        };
        worker = new Thread(task, "Stamina Bootstrap Admin Network Advertiser");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.setDaemon(false);
        worker.start();
    }

    private void sendUdp(DatagramSocket so, InetAddress addr, byte[] payload) throws IOException {
        final DatagramPacket pkt = new DatagramPacket(payload, 0, payload.length,
                addr, DISCOVERY_UDP_PORT);
        so.send(pkt);
    }

    public void stop() {
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1000);
            } catch (InterruptedException ignore) {
            }
        }
    }
}
