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

import org.osgi.service.provisioning.ProvisioningService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * {@link ProvisioningService} implementation using a bootstrap package.
 *
 * @author Stamina Framework developers
 */
class BootstrapProvisioningService implements ProvisioningService {
    private final Path bootstrapPackage;
    private final Set<String> entries = new HashSet<>(4);
    private String agentBundle;

    public BootstrapProvisioningService(final Path bootstrapPackage) throws IOException {
        this.bootstrapPackage = bootstrapPackage;

        // Index bootstrap package entries.
        try (final ZipFile pkg = new ZipFile(bootstrapPackage.toFile())) {
            for (final Enumeration<? extends ZipEntry> zipEntries = pkg.entries(); zipEntries.hasMoreElements(); ) {
                final ZipEntry ze = zipEntries.nextElement();
                if (PROVISIONING_START_BUNDLE.equals(ze.getName())) {
                    final byte[] rawAgentBundle = new byte[(int) ze.getSize()];
                    try (final InputStream in = pkg.getInputStream(ze)) {
                        final byte[] buf = new byte[2048];
                        for (int bytesRead, totalBytesRead = 0; (bytesRead = in.read(buf)) != -1; totalBytesRead += bytesRead) {
                            System.arraycopy(buf, 0, rawAgentBundle, totalBytesRead, bytesRead);
                        }
                    }
                    agentBundle = new String(rawAgentBundle, "UTF-8");
                }
                entries.add(ze.getName());
            }
        }

    }

    @Override
    public Dictionary getInformation() {
        return new Dictionary() {
            @Override
            public int size() {
                return entries.size();
            }

            @Override
            public boolean isEmpty() {
                return entries.isEmpty();
            }

            @Override
            public Enumeration keys() {
                return Collections.enumeration(entries);
            }

            @Override
            public Enumeration elements() {
                final Collection<byte[]> elements = new ArrayList<>(entries.size());
                for (final String entry : entries) {
                    elements.add((byte[]) get(entry));
                }
                return Collections.enumeration(elements);
            }

            @Override
            public Object get(Object key) {
                if (PROVISIONING_START_BUNDLE.equals(key)) {
                    return agentBundle;
                }

                // Lazily load entry content.
                try (final ZipFile pkg = new ZipFile(bootstrapPackage.toFile())) {
                    final ZipEntry ze = pkg.getEntry(key.toString());
                    if (ze == null) {
                        return null;
                    }
                    final byte[] content = new byte[(int) ze.getSize()];
                    final InputStream in = pkg.getInputStream(ze);
                    final byte[] buf = new byte[4096];
                    for (int bytesRead, totalBytesRead = 0; (bytesRead = in.read(buf)) != -1; totalBytesRead += bytesRead) {
                        System.arraycopy(buf, 0, content, totalBytesRead, bytesRead);
                    }
                    return content;
                } catch (IOException e) {
                    throw new RuntimeException("Error while reading bootstrap package entry: " + key, e);
                }
            }

            @Override
            public Object put(Object key, Object value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object remove(Object key) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public void setInformation(Dictionary info) {
    }

    @Override
    public void addInformation(Dictionary info) {
    }

    @Override
    public void addInformation(ZipInputStream zis) throws IOException {
    }
}
