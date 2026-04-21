package ca.ibodrov.concord.mcp;

/*-
 * ~~~~~~
 * Concord MCP Server Plugin
 * ------
 * Copyright (C) 2026 Ivan Bodrov <ibodrov@gmail.com>
 * ------
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
 * ======
 */

import static com.google.inject.Scopes.SINGLETON;
import static com.walmartlabs.concord.server.Utils.bindJaxRsResource;

import com.google.inject.Binder;
import com.google.inject.Module;
import javax.inject.Named;

@Named
public class PluginModule implements Module {

    @Override
    public void configure(Binder binder) {
        bindJaxRsResource(binder, McpResource.class);
        binder.bind(McpToolRegistry.class).in(SINGLETON);
        binder.bind(ConcordCrudTools.class).in(SINGLETON);
        binder.bind(ConcordProcessTools.class).in(SINGLETON);
        binder.bind(ConcordLogTools.class).in(SINGLETON);
    }
}
