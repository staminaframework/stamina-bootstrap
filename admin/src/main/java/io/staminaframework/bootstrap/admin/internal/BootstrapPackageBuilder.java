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

    public void build(Path bootstrapPackageFile, List<String> addonUrls) throws Exception {
        try (final ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bootstrapPackageFile))) {
            final byte[] buffer = new byte[4096];

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
}
