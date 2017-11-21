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
import picocli.CommandLine;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Command for building a bootstrap package.
 *
 * @author Stamina Framework developers
 */
@Component(service = Command.class,
        property = CommandConstants.COMMAND + "=bootstrap:build")
public class BootstrapBuildCommand implements Command {
    @CommandLine.Command(name = "bootstrap:build",
            description = "Build a bootstrap package including addons.")
    private static class Opts {
        @CommandLine.Parameters(description = "Bootstrap destination file", paramLabel = "<bootstrap file>")
        public File outputFile = new File("bootstrap.pkg");
        @CommandLine.Option(description = "Addon URL to include", paramLabel = "<addon URL>",
                names = {"-a", "--addon"})
        public String[] addonUrls = new String[0];
        @CommandLine.Option(paramLabel = "<overlay>", description = "Set overlay to apply (ZIP archive or directory)",
                names = {"-o", "--overlay"})
        public File overlayFile = new File("bootstrap.overlay.zip");
        @CommandLine.Option(description = "Show command usage", names = {"-h", "--help"}, usageHelp = true)
        public boolean showHelp = false;
    }

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
        CommandLine.usage(new Opts(), out);
    }

    @Override
    public boolean execute(Context context) throws Exception {
        final Opts opts;
        try {
            opts = CommandLine.populateCommand(new Opts(), context.arguments());
        } catch (CommandLine.PicocliException e) {
            help(context.out());
            return false;
        }

        final Path bootstrapPackageFile = opts.outputFile.toPath();
        final List<String> addonUrls = Arrays.asList(opts.addonUrls);

        context.out().println("Generating bootstrap package: " + bootstrapPackageFile);
        final Path overlay = opts.overlayFile == null ? null : opts.overlayFile.toPath();
        new BootstrapPackageBuilder(bundleContext).build(bootstrapPackageFile, overlay, addonUrls);

        return false;
    }
}
