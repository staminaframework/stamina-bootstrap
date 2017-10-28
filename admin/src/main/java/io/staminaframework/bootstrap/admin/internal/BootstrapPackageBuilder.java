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
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.osgi.service.log.LogService;
import org.osgi.service.provisioning.ProvisioningService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Internal component building bootstrap package.
 *
 * @author Stamina Framework developers
 */
@Component(configurationPid = "io.staminaframework.bootstrap.admin")
public class BootstrapPackageBuilder {
    @interface Config {
        /**
         * Addon list to include into the bootstrap package.
         * <p>
         * Addons are resolved using registered OBR repositories.
         * <p>
         * Each addon reference must follow this pattern:
         * <code>addon.symbolic.name/addon.version</code> or <code>addon.symbolic.name</code>.
         * For example: <code>io.staminaframework.addons.shell/1.0.0</code>.
         */
        String[] addons() default "";
    }

    @Reference
    private LogService logService;
    private HttpService httpService;
    private List<String> addonUrls = Collections.emptyList();
    private BundleContext bundleContext;
    private Thread bootstrapPackageBuilderThread;
    private final Set<String> httpEndpoints = new HashSet<>(2);
    private ServiceRegistration<BootstrapPackage> bootstrapPackageReg;

    @Reference
    void bindHttpService(HttpService httpService, Map<String, Object> props) {
        this.httpService = httpService;

        httpEndpoints.clear();
        final Object rawEndpoints = props.get("osgi.http.service.endpoints");
        if (rawEndpoints instanceof String) {
            httpEndpoints.add(
                    newUrl((String) rawEndpoints, BootstrapAdminConstants.BOOTSTRAP_PACKAGE_PATH));
        } else if (rawEndpoints instanceof String[]) {
            final String[] endpoints = (String[]) rawEndpoints;
            for (int i = 0; i < endpoints.length; ++i) {
                httpEndpoints.add(
                        newUrl(endpoints[i], BootstrapAdminConstants.BOOTSTRAP_PACKAGE_PATH));
            }
        }
    }

    private String newUrl(String base, String target) {
        final StringBuilder buf = new StringBuilder(base);
        if (base.endsWith("/")) {
            buf.delete(buf.length() - 1, buf.length());
        }
        if (!target.startsWith("/")) {
            buf.append("/");
        }
        return buf.append(target).toString();
    }

    @Activate
    void activate(BundleContext bundleContext, Config config) {
        this.bundleContext = bundleContext;

        final Runnable bootstapTask = () -> {
            try {
                buildBootstrapPackage();
            } catch (Exception e) {
                logService.log(LogService.LOG_ERROR,
                        "Error while building bootstrap package", e);
            }
        };

        if (config.addons() != null) {
            addonUrls = Arrays.stream(config.addons())
                    .map(a -> "addon:" + a)
                    .collect(Collectors.toList());
        }

        bootstrapPackageBuilderThread = new Thread(bootstapTask, "Stamina Bootstrap Package Builder");
        bootstrapPackageBuilderThread.setPriority(Thread.MIN_PRIORITY);
        bootstrapPackageBuilderThread.setDaemon(true);
        bootstrapPackageBuilderThread.start();
    }

    @Deactivate
    void deactivate() {
        if (bootstrapPackageBuilderThread != null) {
            bootstrapPackageBuilderThread.interrupt();
            try {
                bootstrapPackageBuilderThread.join(1000 * 10);
            } catch (InterruptedException ignore) {
            }
            bootstrapPackageBuilderThread = null;
        }
        if (bootstrapPackageReg != null) {
            bootstrapPackageReg.unregister();
            bootstrapPackageReg = null;
        }
        try {
            httpService.unregister(BootstrapAdminConstants.BOOTSTRAP_PACKAGE_PATH);
        } catch (IllegalArgumentException ignore) {
        }
        this.bundleContext = null;
    }

    private void buildBootstrapPackage() throws Exception {
        logService.log(LogService.LOG_INFO, "Building bootstrap package with addons: " + addonUrls);

        final Path bootstrapPkg = bundleContext.getDataFile("bootstrap.pkg").toPath();
        try (final ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(bootstrapPkg.toFile()))) {
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

        logService.log(LogService.LOG_INFO, "Bootstrap package is ready");
        exposeBootstrapPackage(bootstrapPkg);
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

    private void exposeBootstrapPackage(Path bootstrapPkg) throws IOException {
        final URL bootstrapPkgUrl = bootstrapPkg.toUri().toURL();
        final HttpContext httpContext = new HttpContext() {
            @Override
            public boolean handleSecurity(HttpServletRequest request, HttpServletResponse response) throws IOException {
                return true;
            }

            @Override
            public URL getResource(String name) {
                return bootstrapPkgUrl;
            }

            @Override
            public String getMimeType(String name) {
                return BootstrapAdminConstants.BOOTSTRAP_PACKAGE_MIME_TYPE;
            }
        };
        try {
            httpService.registerResources(BootstrapAdminConstants.BOOTSTRAP_PACKAGE_PATH,
                    bootstrapPkg.toString(), httpContext);
            bootstrapPackageReg =
                    bundleContext.registerService(BootstrapPackage.class, new BootstrapPackage() {
                        @Override
                        public Set<String> endpoints() {
                            return Collections.unmodifiableSet(httpEndpoints);
                        }
                    }, null);
        } catch (NamespaceException e) {
            logService.log(LogService.LOG_ERROR,
                    "Failed to register bootstrap package as a web resource", e);
        }
    }
}
