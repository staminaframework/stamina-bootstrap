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

import java.util.Set;

/**
 * Internal service published once a bootstrap package is made available.
 * This service is not published if there is no endpoint.
 *
 * @author Stamina Framework developers
 */
public interface BootstrapPackage {
    /**
     * Get endpoint URls where a bootstrap package can be downloaded.
     *
     * @return bootstrap package endpoints
     */
    Set<String> endpoints();
}
