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

import org.osgi.framework.BundleContext;
import org.osgi.service.provisioning.ProvisioningService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Internal component building bootstrap package.
 *
 * @author Stamina Framework developers
 */
class BootstrapPackageBuilder {
    private final BundleContext bundleContext;

    public BootstrapPackageBuilder(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    public void build(Path bootstrapPackageFile, Path overlay, List<String> addonUrls) throws Exception {
        final byte[] buffer = new byte[4096];

        Path overlayFile = null;
        if (overlay != null) {
            if (Files.isRegularFile(overlay)
                    && (overlay.getFileName().endsWith(".zip") || overlay.getFileName().endsWith(".jar"))) {
                overlayFile = overlay;
            } else if (Files.isDirectory(overlay)) {
                final Set<Path> filesToInclude;
                try (final Stream<Path> p = Files.walk(overlay)) {
                    filesToInclude = p.filter(Files::isRegularFile).collect(Collectors.toSet());
                }
                if (!filesToInclude.isEmpty()) {
                    overlayFile = Files.createTempFile("stamina.runtime.overlay-", ".zip");
                    overlayFile.toFile().deleteOnExit();
                    try (final ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(overlayFile))) {
                        for (final Path f : filesToInclude) {
                            final String entryName = overlay.relativize(f).toString().replace('\\', '/');
                            final ZipEntry ze = new ZipEntry(entryName);
                            zip.putNextEntry(ze);
                            copyResource(f, zip, buffer);
                            zip.closeEntry();
                        }
                    }
                }
            }
        }

        try (final ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bootstrapPackageFile))) {
            ZipEntry ze = new ZipEntry("stamina.bootstrap.agent.jar");
            zip.putNextEntry(ze);
            copyResource(getBootstrapPackageEntry("stamina.bootstrap.agent.jar"), zip, buffer);
            zip.closeEntry();

            ze = new ZipEntry(ProvisioningService.PROVISIONING_START_BUNDLE);
            zip.putNextEntry(ze);
            zip.write("stamina.bootstrap.agent.jar".getBytes("UTF-8"));
            zip.closeEntry();

            ze = new ZipEntry("stamina.runtime.zip");
            zip.putNextEntry(ze);
            copyResource(getBootstrapPackageEntry("stamina.runtime.zip"), zip, buffer);
            zip.closeEntry();

            ze = new ZipEntry("stamina.runtime.tar.gz");
            zip.putNextEntry(ze);
            copyResource(getBootstrapPackageEntry("stamina.runtime.tar.gz"), zip, buffer);
            zip.closeEntry();

            if (overlayFile != null) {
                ze = new ZipEntry("stamina.runtime.overlay.zip");
                zip.putNextEntry(ze);
                Files.copy(overlayFile, zip);
            }

            int addonCounter = 0;
            for (final String addonUrl : addonUrls) {
                final URL u = new URL(addonUrl);
                ze = new ZipEntry("stamina.addon." + addonCounter++ + ".esa");
                zip.putNextEntry(ze);
                copyResource(u, zip, buffer);
                zip.closeEntry();
            }
        }
    }

    private URL getBootstrapPackageEntry(String name) {
        return bundleContext.getBundle().getEntry("/OSGI-INF/bootstrap-package/" + name);
    }

    private void copyResource(URL source, OutputStream target, byte[] buffer) throws IOException {
        try (final InputStream in = source.openStream()) {
            for (int bytesRead; (bytesRead = in.read(buffer)) != -1; ) {
                target.write(buffer, 0, bytesRead);
            }
        }
    }

    private void copyResource(Path file, OutputStream target, byte[] buffer) throws IOException {
        try (final InputStream in = Files.newInputStream(file)) {
            for (int bytesRead; (bytesRead = in.read(buffer)) != -1; ) {
                target.write(buffer, 0, bytesRead);
            }
        }
    }
}
