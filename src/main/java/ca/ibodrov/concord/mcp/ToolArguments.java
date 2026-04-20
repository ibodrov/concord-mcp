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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

final class ToolArguments {

    private final Map<String, Object> values;

    ToolArguments(Map<String, Object> values) {
        this.values = values;
    }

    String requireString(String name) {
        var value = optionalString(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        return value;
    }

    String optionalString(String name) {
        var value = values.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("'" + name + "' must be a string");
    }

    UUID optionalUuid(String name) {
        var value = optionalString(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + name + "' must be a UUID");
        }
    }

    boolean optionalBoolean(String name, boolean defaultValue) {
        var value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("'" + name + "' must be a boolean");
    }

    <T extends Enum<T>> T optionalEnum(String name, Class<T> type, T defaultValue) {
        var value = optionalString(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + name + "' must be one of " + List.of(type.getEnumConstants()));
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> optionalObject(String name) {
        var value = values.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        throw new IllegalArgumentException("'" + name + "' must be an object");
    }

    List<String> optionalStringList(String name) {
        var value = values.get(name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(v -> {
                        if (!(v instanceof String s)) {
                            throw new IllegalArgumentException("'" + name + "' must contain strings");
                        }
                        return s;
                    })
                    .collect(Collectors.toList());
        }
        throw new IllegalArgumentException("'" + name + "' must be an array of strings");
    }
}
