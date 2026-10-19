package com.unrealinterface.unreal;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Enables ModelContextProtocol and AllToolsets in a .uproject without dropping other fields. */
final class UprojectPlugins {

    static final List<String> PLUGIN_NAMES = List.of("ModelContextProtocol", "AllToolsets");

    record Parsed(ObjectNode root, String error) {
    }

    /** {@code content} is set only when the plugin list changed. {@code error} means do not write. */
    record Edit(String content, String error) {
        static Edit unchanged() {
            return new Edit(null, null);
        }

        boolean shouldWrite() {
            return content != null;
        }
    }

    private UprojectPlugins() {
    }

    static Parsed parse(String original) {
        String text = JsonTexts.stripBom(original);
        try {
            JsonNode node = JsonTexts.MAPPER.readTree(text);
            if (!(node instanceof ObjectNode object)) {
                return new Parsed(null, "is not a JSON object and was left unchanged");
            }
            return new Parsed(object, null);
        } catch (JsonProcessingException ex) {
            return new Parsed(null, "is not valid JSON and was left unchanged");
        }
    }

    static String association(ObjectNode root) {
        JsonNode node = root.get("EngineAssociation");
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual() || node.isNumber()) {
            return node.asText().trim();
        }
        return "";
    }

    static Edit enable(ObjectNode root) throws IOException {
        JsonNode plugins = root.get("Plugins");
        ArrayNode array;
        boolean changed = false;
        if (plugins == null || plugins.isNull()) {
            array = root.putArray("Plugins");
            changed = true;
        } else if (plugins instanceof ArrayNode existing) {
            array = existing;
        } else {
            return new Edit(null, "has Plugins set to a value that is not an array, so the file was left unchanged");
        }
        for (String name : PLUGIN_NAMES) {
            ObjectNode match = null;
            for (JsonNode node : array) {
                if (node instanceof ObjectNode object && name.equals(text(object.get("Name")))) {
                    match = object;
                    break;
                }
            }
            if (match == null) {
                ObjectNode added = array.addObject();
                added.put("Name", name);
                added.put("Enabled", true);
                changed = true;
            } else {
                JsonNode enabled = match.get("Enabled");
                if (enabled == null || !enabled.isBoolean() || !enabled.booleanValue()) {
                    match.put("Enabled", true);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return Edit.unchanged();
        }
        return new Edit(JsonTexts.pretty(root), null);
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
