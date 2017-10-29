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

import org.apache.felix.framework.util.FelixConstants;
import org.osgi.framework.*;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
import org.osgi.service.log.LogService;
import org.osgi.service.provisioning.ProvisioningService;
import picocli.CommandLine;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Application entry point.
 *
 * @author Stamina Framework developers
 */
public class Main {
    private static final String DEFAULT_BOOTSTRAP_PACKAGE = "http://localhost:8080/bootstrap.pkg";
    private static Framework fwk;

    @CommandLine.Command(name = "io.staminaframework.bootstrap",
            description = "Bootstrap a Stamina Framework platform.")
    private static class Options {
        @CommandLine.Option(names = {"-f", "--from"}, usageHelp = true, description = "Set URL to bootstrap package")
        public String from;
        @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show command usage")
        public boolean showHelp;
        @CommandLine.Option(names = {"-c", "--clean"}, usageHelp = true, description = "Start platform from scratch")
        public boolean clean;
        @CommandLine.Option(names = {"-d", "--debug"}, usageHelp = true, description = "Enable verbose debugging")
        public boolean debug;
    }

    public static void main(String[] args) {
        // Parsing command-line arguments.
        Options opts = null;
        try {
            opts = CommandLine.populateCommand(new Options(), args);
        } catch (CommandLine.PicocliException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
        if (opts.showHelp) {
            CommandLine.usage(opts, System.out);
            return;
        }

        final ConsoleLogService logger = ConsoleLogService.INSTANCE;
        logger.setDebug(opts.debug);
        logger.log(LogService.LOG_INFO, "Initializing");

        // Generate an unique launcher id.
        // This id may be used by a Bootstrap Admin instance to return
        // a custom bootstrap package.
        String launcherId = null;
        boolean launchedIdUpdated = false;
        final Path staminaUserDir = FileSystems.getDefault().getPath(System.getProperty("user.home")).resolve(".stamina");
        final Path bootstrapDir = staminaUserDir.resolve("bootstrap");
        try {
            Files.createDirectories(bootstrapDir);
        } catch (IOException e) {
            logger.log(LogService.LOG_ERROR,
                    "Error while creating bootstrap user configuration directory: " + bootstrapDir, e);
            System.exit(1);
            return;
        }
        final Path confFile = bootstrapDir.resolve("launcher.properties");
        if (Files.exists(confFile)) {
            final Properties launcherProps = new Properties();
            try (final InputStream in = Files.newInputStream(confFile)) {
                launcherProps.load(in);
            } catch (IOException e) {
                logger.log(LogService.LOG_WARNING,
                        "Failed to load bootstrap user configuration: " + confFile, e);
            }
            launcherId = launcherProps.getProperty("launcher.uuid");
        }
        if (launcherId == null || launcherId.length() == 0) {
            launcherId = UUID.randomUUID().toString();
            try (final OutputStream out = Files.newOutputStream(confFile)) {
                final Properties launcherProps = new Properties();
                launcherProps.setProperty("launcher.uuid", launcherId);
                launcherProps.store(out, "Bootstrap configuration");
            } catch (IOException e) {
                logger.log(LogService.LOG_WARNING,
                        "Failed to update bootstrap user configuration: " + confFile, e);
            }
        }

        final Path homeDir = FileSystems.getDefault().getPath(System.getProperty("user.dir"));
        final Path cacheDir = homeDir.resolve("cache");
        if (opts.clean) {
            logger.log(LogService.LOG_INFO, "Cleaning cache");
            try {
                deleteDir(cacheDir);
            } catch (IOException e) {
                logger.log(LogService.LOG_ERROR,
                        "Cannot delete cache directory: " + cacheDir, e);
                System.exit(1);
            }
        }

        final Map<String, String> fwkConf = new HashMap<>(1);
        fwkConf.put(Constants.FRAMEWORK_STORAGE, cacheDir.toString());
        fwkConf.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA,
                "org.osgi.service.log;version=1.3, " +
                        "org.osgi.service.provisioning;version=1.2");
        fwkConf.put(FelixConstants.LOG_LEVEL_PROP, "0");

        try {
            final FrameworkFactory fwkFactory = newFrameworkFactory();
            fwk = fwkFactory.newFramework(fwkConf);
            fwk.init();
        } catch (BundleException e) {
            logger.log(LogService.LOG_ERROR, "Error while initializing OSGi framework", e);
            System.exit(1);
        }

        assert fwk != null;
        final BundleContext ctx = fwk.getBundleContext();
        ctx.registerService(LogService.class, logger, null);

        Runtime.getRuntime().addShutdownHook(new Thread("Stamina Bootstrap Shutdown Hook") {
            @Override
            public void run() {
                logger.log(LogService.LOG_INFO, "Stopping OSGi framework");
                try {
                    fwk.stop();
                    fwk.waitForStop(10000);
                } catch (Exception e) {
                    logger.log(LogService.LOG_ERROR,
                            "Error while stopping OSGi framework", e);
                }
            }
        });

        // Set a default HTTP user agent.
        final String httpUserAgent = "StaminaBootstrap/" + Version.VERSION
                + " (" + System.getProperty("os.name") + "; " + System.getProperty("os.arch")
                + "; " + System.getProperty("java.runtime.name") + "/" + System.getProperty("java.runtime.version")
                + ")";

        // Download bootstrap package to local cache.
        final Path localBootstrapPackage = ctx.getDataFile("bootstrap.pkg").toPath();
        if (!Files.exists(localBootstrapPackage)) {
            try {
                final Set<URL> urls = new HashSet<>(2);
                if (opts.from == null) {
                    urls.add(new URL(DEFAULT_BOOTSTRAP_PACKAGE));
                }

                if (opts.from != null) {
                    if ("bootstrap:network".equals(opts.from)) {
                        while (urls.isEmpty()) {
                            final BootstrapAdminNetworkDiscoverer discoverer = new BootstrapAdminNetworkDiscoverer(logger, null);
                            logger.log(LogService.LOG_INFO,
                                    "Looking for network bootstrap package");
                            final Set<URL> foundUrls = discoverer.discover(1000 * 10);
                            urls.addAll(foundUrls);

                            if (urls.isEmpty()) {
                                logger.log(LogService.LOG_WARNING, "No bootstrap package found");
                            }
                        }
                    } else {
                        urls.add(new URL(opts.from));
                    }
                }

                // Try to download bootstrap package with any of these URLs.
                for (final URL u : urls) {
                    logger.log(LogService.LOG_INFO, "Using bootstrap package: " + u);
                    final URLConnection conn = u.openConnection();
                    if ("http".equals(u.getProtocol()) || "https".equals(u.getProtocol())) {
                        conn.setRequestProperty("User-Agent", httpUserAgent);
                        conn.setRequestProperty("StaminaBootstrap-Id", launcherId);
                    }
                    try (final InputStream in = conn.getInputStream()) {
                        Files.copy(in, localBootstrapPackage);
                        // We were able to use this URL: we can stop here.
                        break;
                    }
                }
            } catch (IOException e) {
                logger.log(LogService.LOG_ERROR, "Error while downloading bootstrap package", e);
                try {
                    Files.delete(localBootstrapPackage);
                } catch (IOException ignore) {
                }
                System.exit(1);
            }
        }

        try {
            logger.log(LogService.LOG_INFO, "Reading bootstrap package");
            final ProvisioningService ps = new BootstrapProvisioningService(localBootstrapPackage);
            ctx.registerService(ProvisioningService.class, ps, null);

            final boolean installAgent = ctx.getBundle("bootstrap:agent") == null;
            if (installAgent) {
                logger.log(LogService.LOG_INFO, "Installing bootstrap agent");

                final String agentKey = (String) ps.getInformation().get(ProvisioningService.PROVISIONING_START_BUNDLE);
                if (agentKey == null) {
                    throw new IOException("Unable to locate agent in bootstrap package");
                }

                final byte[] agentContent = (byte[]) ps.getInformation().get(agentKey);
                if (agentContent == null) {
                    throw new IOException("No content found for agent in bootstrap package");
                }
                final Bundle agent = ctx.installBundle("bootstrap:agent", new ByteArrayInputStream(agentContent));
                agent.start();
            }
        } catch (IOException e) {
            logger.log(LogService.LOG_ERROR, "Error while reading bootstrap package", e);
            try {
                Files.delete(localBootstrapPackage);
            } catch (IOException ignore) {
            }
            System.exit(1);
        } catch (BundleException e) {
            logger.log(LogService.LOG_ERROR, "Error while installing bootstrap agent", e);
            System.exit(1);
        }

        final FrameworkListener fwkListener = event -> {
            if (event.getType() == FrameworkEvent.ERROR) {
                logger.log(LogService.LOG_ERROR, "Fatal error", event.getThrowable());
                System.exit(1);
            }
        };
        ctx.addFrameworkListener(fwkListener);

        try {
            fwk.start();
        } catch (BundleException e) {
            logger.log(LogService.LOG_ERROR, "Error while starting OSGi framework", e);
            System.exit(1);
        }

        try {
            fwk.waitForStop(0);
        } catch (InterruptedException e) {
            logger.log(LogService.LOG_INFO, "Shutting down");
        }
    }

    private static FrameworkFactory newFrameworkFactory() {
        final ServiceLoader<FrameworkFactory> fwkFactoryLoader =
                ServiceLoader.load(FrameworkFactory.class);
        FrameworkFactory fwkFactory = null;
        for (final Iterator<FrameworkFactory> i = fwkFactoryLoader.iterator(); i.hasNext(); ) {
            fwkFactory = i.next();
            break;
        }
        if (fwkFactory == null) {
            throw new RuntimeException("No OSGi framework found");
        }
        return fwkFactory;
    }

    private static void deleteDir(Path dir) throws IOException {
        Files.walk(dir, FileVisitOption.FOLLOW_LINKS)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }
}
