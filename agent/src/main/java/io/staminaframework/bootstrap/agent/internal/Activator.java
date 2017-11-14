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

package io.staminaframework.bootstrap.agent.internal;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.service.log.LogService;
import org.osgi.service.provisioning.ProvisioningService;
import org.osgi.util.tracker.ServiceTracker;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Bundle activator.
 *
 * @author Stamina Framework developers
 */
public class Activator implements BundleActivator {
    /**
     * Return code used by runtime when process restart is required.
     */
    private static final int RESTART_EXIT_CODE = 100;
    private Thread procThread;
    private volatile Process proc;
    private LogService logService;
    private ProvisioningService provisioningService;

    @Override
    public void start(BundleContext context) throws Exception {
        logService = lookupService(context, LogService.class);
        provisioningService = lookupService(context, ProvisioningService.class);

        logService.log(LogService.LOG_INFO, "Starting bootstrap agent: "
                + context.getBundle().getSymbolicName() + "/"
                + context.getBundle().getVersion());

        final Path runtimeDir = context.getDataFile("runtime").toPath();

        final boolean runtimeInstalled =
                context.getDataFile("runtime.installed").exists();
        if (runtimeInstalled) {
            logService.log(LogService.LOG_DEBUG, "Using existing runtime");
        } else {
            logService.log(LogService.LOG_DEBUG, "No runtime found: installing new one");
            installRuntime(runtimeDir);
            initConf(context, runtimeDir);
            logService.log(LogService.LOG_DEBUG, "Runtime successfully installed");

            // Mark runtime as installed.
            context.getDataFile("runtime.installed").createNewFile();
        }

        final Path launcherFile;
        if (isOsWindows()) {
            launcherFile = runtimeDir.resolve("bin/stamina.bat");
        } else {
            launcherFile = runtimeDir.resolve("bin/stamina");
        }
        if (!Files.exists(launcherFile)) {
            throw new RuntimeException("Runtime launcher not found");
        }

        final Runnable procRunner = () -> {
            try {
                for (boolean running = true; running; ) {
                    logService.log(LogService.LOG_INFO, "Starting runtime");
                    proc = new ProcessBuilder(launcherFile.toString())
                            .directory(runtimeDir.toFile())
                            .inheritIO()
                            .start();
                    final int exitCode = proc.waitFor();
                    if (exitCode == RESTART_EXIT_CODE) {
                        logService.log(LogService.LOG_INFO, "Restarting runtime");
                        // Wait some time before we actually restart process.
                        Thread.sleep(1000);
                    } else {
                        running = false;
                    }
                }
            } catch (InterruptedException ignore) {
            } catch (Exception e) {
                logService.log(LogService.LOG_ERROR, "Error while starting runtime", e);
            }
            logService.log(LogService.LOG_INFO, "Runtime exit");
            proc = null;

            // Stop framework since runtime process has just gone.
            try {
                context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION).stop();
            } catch (BundleException ignore) {
            }
        };
        procThread = new Thread(procRunner, "Stamina Bootstrap Agent");
        procThread.setDaemon(false);
        procThread.setPriority(Thread.NORM_PRIORITY);
        procThread.start();
    }

    private void installRuntime(Path runtimeDir) throws IOException {
        final String type;
        if (isOsWindows()) {
            type = "zip";
        } else {
            type = "tar.gz";
        }
        logService.log(LogService.LOG_DEBUG, "Using runtime type: " + type);

        final Dictionary<String, Object> psInfo = provisioningService.getInformation();
        byte[] provisioningEntry = (byte[]) psInfo.get("stamina.runtime." + type);
        if (provisioningEntry == null) {
            throw new RuntimeException("Missing runtime URL in provisioning data");
        }

        logService.log(LogService.LOG_DEBUG, "Extracting runtime");
        if ("zip".equals(type)) {
            try (final ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(provisioningEntry))) {
                final byte[] buf = new byte[4096];
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    final String entryNameTrimmed = entry.getName().substring(entry.getName().indexOf('/') + 1);
                    if (entryNameTrimmed.length() != 0 && !entryNameTrimmed.endsWith("/")) {
                        logService.log(LogService.LOG_DEBUG,
                                "Extracting file: " + entryNameTrimmed);
                        final Path outFile = runtimeDir.resolve(entryNameTrimmed);
                        Files.createDirectories(outFile.getParent());
                        try (final OutputStream out = new FileOutputStream(outFile.toFile())) {
                            for (int bytesRead; (bytesRead = zip.read(buf)) != -1; ) {
                                out.write(buf, 0, bytesRead);
                            }
                        }
                    }
                    zip.closeEntry();
                }
            }
        } else {
            final Path tarFile = Files.createTempFile("stamina-runtime-", ".tar");
            try {
                try (final GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(provisioningEntry))) {
                    Files.copy(in, tarFile, StandardCopyOption.REPLACE_EXISTING);
                }
                try (final TarArchiveInputStream in = new TarArchiveInputStream(new FileInputStream(tarFile.toFile()))) {
                    final byte[] buf = new byte[4096];
                    for (TarArchiveEntry te; (te = in.getNextTarEntry()) != null; ) {
                        if (!te.isDirectory()) {
                            final String name = te.getName().substring(te.getName().indexOf('/') + 1);
                            logService.log(LogService.LOG_DEBUG,
                                    "Extracting file: " + name);
                            final Path outFile = runtimeDir.resolve(name);
                            Files.createDirectories(outFile.getParent());
                            try (final FileOutputStream out = new FileOutputStream(outFile.toFile())) {
                                for (int bytesRead; (bytesRead = in.read(buf)) != -1; ) {
                                    out.write(buf, 0, bytesRead);
                                }
                            }
                        }
                    }
                    // Set execution permission on launcher scripts.
                    final Set<PosixFilePermission> perms = new HashSet<>(2);
                    perms.add(PosixFilePermission.OWNER_READ);
                    perms.add(PosixFilePermission.OWNER_EXECUTE);
                    perms.add(PosixFilePermission.GROUP_READ);
                    perms.add(PosixFilePermission.GROUP_EXECUTE);
                    final Path binDir = runtimeDir.resolve("bin");
                    try (final DirectoryStream<Path> dirStream = Files.newDirectoryStream(binDir)) {
                        for (final Iterator<Path> i = dirStream.iterator(); i.hasNext(); ) {
                            final Path binFile = i.next();
                            Files.setPosixFilePermissions(binFile, perms);
                        }
                    }
                }
            } finally {
                try {
                    Files.delete(tarFile);
                } catch (IOException ignore) {
                }
            }
        }

        final Path addonsDir = runtimeDir.resolve("addons");
        for (int addonCounter = 0; ; ++addonCounter) {
            final String addonKey = "stamina.addon." + addonCounter + ".esa";
            provisioningEntry = (byte[]) psInfo.get(addonKey);
            if (provisioningEntry == null) {
                break;
            }
            final Path addonFile = Files.createTempFile("stamina-addon-", ".esa");
            Files.copy(new ByteArrayInputStream(provisioningEntry), addonFile, StandardCopyOption.REPLACE_EXISTING);

            String addonName = null;
            try (final ZipFile zip = new ZipFile(addonFile.toFile())) {
                final ZipEntry ze = zip.getEntry("OSGI-INF/SUBSYSTEM.MF");
                if (ze != null) {
                    final Manifest man = new Manifest(zip.getInputStream(ze));
                    addonName = man.getMainAttributes().getValue("Subsystem-SymbolicName");
                }
            }
            if (addonName == null) {
                addonName = addonKey;
            }
            final Path renamedAddonFile = addonsDir.resolve(addonName + ".esa");
            logService.log(LogService.LOG_DEBUG, "Extracting addon: " + renamedAddonFile.getFileName());
            Files.move(addonFile, renamedAddonFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private <T> T lookupService(BundleContext context, Class<T> serviceClass) throws InterruptedException {
        final ServiceTracker<T, T> tracker = new ServiceTracker<>(context, serviceClass, null);
        tracker.open();
        final T svc = tracker.waitForService(1000 * 10);
        if (svc == null) {
            throw new RuntimeException("Missing required service: " + serviceClass.getName());
        }
        return svc;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (proc != null) {
            proc.destroy();
            proc = null;
        }
        if (procThread != null) {
            procThread.interrupt();
            try {
                procThread.join(1000 * 10);
            } catch (InterruptedException ignore) {
            }
            procThread = null;
        }
        logService = null;
        provisioningService = null;
    }

    private void initConf(BundleContext context, Path runtimeDir) throws IOException {
        final String initPath = context.getProperty("stamina.bootstrap.init");
        if (initPath == null) {
            return;
        }

        final Path initDir = FileSystems.getDefault().getPath(initPath);
        logService.log(LogService.LOG_INFO,
                "Using configuration directory: " + initDir);

        final Path confDir = runtimeDir.resolve("etc");
        final Path initConfFile = confDir.resolve("org.apache.felix.fileinstall-init.cfg");
        try (final PrintWriter out = new PrintWriter(Files.newBufferedWriter(initConfFile, Charset.forName("UTF-8")))) {
            out.println("# Generated file: DO NOT MODIFY IT!");
            out.println("felix.fileinstall.dir=" + initDir.toString().replace("\\", "/"));
            out.println("felix.fileinstall.filter=.*\\\\.(cfg|config)");
            out.println("felix.fileinstall.poll=1000");
            out.println("felix.fileinstall.log.level=3");
        }
    }

    private static boolean isOsWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
}
