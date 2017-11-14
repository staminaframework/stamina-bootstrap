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

import io.staminaframework.runtime.command.Command;
import io.staminaframework.runtime.command.CommandConstants;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command for building a bootstrap package.
 *
 * @author Stamina Framework developers
 */
@Component(service = Command.class,
        property = CommandConstants.COMMAND + "=bootstrap:build")
public class BootstrapBuildCommand implements Command {
    private BundleContext bundleContext;

    @Activate
    void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    @Deactivate
    void deactivate() {
        this.bundleContext = null;
    }

    @Override
    public void help(PrintStream out) {
        out.println("Build a bootstrap package including addons.");
        out.println("The generated package file can be deployed anywhere.");
        out.println("Usage: bootstrap:build <destination file> [<addon URL>]*");
    }

    @Override
    public boolean execute(Context context) throws Exception {
        if (context.arguments().length == 0) {
            help(context.out());
            return false;
        }

        final String bootstrapPackagePath = context.arguments()[0];
        final Path bootstrapPackageFile = FileSystems.getDefault().getPath(bootstrapPackagePath);

        final List<String> addonUrls = new ArrayList<>(context.arguments().length - 1);
        for (int i = 1; i < context.arguments().length; ++i) {
            addonUrls.add(context.arguments()[i]);
        }

        context.out().println("Generating bootstrap package: " + bootstrapPackageFile);
        new BootstrapPackageBuilder(bundleContext).build(bootstrapPackageFile, addonUrls);

        return false;
    }
}
