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

import org.osgi.framework.ServiceReference;
import org.osgi.service.log.LogService;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link LogService} implementation which outputs to <code>stdout</code>.
 *
 * @author Stamina Framework developers
 */
class ConsoleLogService implements LogService {
    public static final ConsoleLogService INSTANCE = new ConsoleLogService();
    private static final Map<Integer, String> LEVELS = new HashMap<>(4);

    static {
        LEVELS.put(LOG_DEBUG, "DEBUG");
        LEVELS.put(LOG_INFO, "INFO");
        LEVELS.put(LOG_WARNING, "WARN");
        LEVELS.put(LOG_ERROR, "ERROR");
    }

    private boolean debug;

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    @Override
    public void log(int level, String message) {
        log(level, message, null);
    }

    @Override
    public void log(int level, String message, Throwable exception) {
        if (level == LOG_DEBUG && !debug) {
            return;
        }

        final String levelStr = LEVELS.getOrDefault(level, "INFO");
        final String entry = String.format("[%-5s] %s", levelStr, message);
        System.out.println(entry);
        if (exception != null) {
            exception.printStackTrace(System.out);
        }
    }

    @Override
    public void log(ServiceReference sr, int level, String message) {
        log(level, message, null);
    }

    @Override
    public void log(ServiceReference sr, int level, String message, Throwable exception) {
        log(level, message, exception);
    }
}
