package com.unrealinterface.unreal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Writes the Unreal MCP client entry in {@code .mcp.json} and {@code .codex/config.toml}. */
final class McpClientConfig {

    static final String SERVER_NAME = "unreal-mcp";
    private static final String CODEX_TABLE = "[mcp_servers.unreal-mcp]";
    private static final Pattern URL_LINE = Pattern.compile("^(\\s*)url\\s*=\\s*(.*)$");
    private static final Pattern COMMAND_LINE = Pattern.compile("^\\s*command\\s*=");

    /** {@code content} is set only when the file should be written. */
    record Edit(String content, String error, boolean customCommand) {
        static Edit unchanged() {
            return new Edit(null, null, false);
        }

        static Edit unchangedCustom() {
            return new Edit(null, null, true);
        }

        static Edit write(String content) {
            return new Edit(content, null, false);
        }

        static Edit failed(String error) {
            return new Edit(null, error, false);
        }

        boolean shouldWrite() {
            return content != null;
        }

        boolean failed() {
            return error != null;
        }
    }

    private McpClientConfig() {
    }

    static Edit mcpJson(String existing, String url) throws IOException {
        if (existing == null) {
            ObjectNode root = JsonTexts.MAPPER.createObjectNode();
            ObjectNode servers = root.putObject("mcpServers");
            putServer(servers, url);
            return Edit.write(JsonTexts.pretty(root));
        }
        if (existing.indexOf('\0') >= 0) {
            return Edit.failed("is not a text file and was left unchanged");
        }
        JsonNode parsed;
        try {
            parsed = JsonTexts.MAPPER.readTree(JsonTexts.stripBom(existing));
        } catch (JsonProcessingException ex) {
            return Edit.failed("is not valid JSON and was left unchanged");
        }
        if (!(parsed instanceof ObjectNode root)) {
            return Edit.failed("is not a JSON object and was left unchanged");
        }
        JsonNode serversNode = root.get("mcpServers");
        ObjectNode servers;
        if (serversNode == null || serversNode.isNull()) {
            servers = root.putObject("mcpServers");
        } else if (serversNode instanceof ObjectNode object) {
            servers = object;
        } else {
            return Edit.failed("has mcpServers set to a value that is not an object, so the file was left unchanged");
        }
        JsonNode current = servers.get(SERVER_NAME);
        if (current instanceof ObjectNode entry
                && "http".equals(entry.path("type").asText())
                && url.equals(entry.path("url").asText())) {
            return Edit.unchanged();
        }
        putServer(servers, url);
        return Edit.write(JsonTexts.pretty(root));
    }

    static Edit codexToml(String existing, String url) {
        if (existing == null) {
            return Edit.write(CODEX_TABLE + "\nurl = \"" + tomlString(url) + "\"\n");
        }
        if (existing.indexOf('\0') >= 0) {
            return Edit.failed(".codex/config.toml is not a text file and was left unchanged");
        }
        String newline = existing.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = split(existing);
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (CODEX_TABLE.equals(lines.get(i).trim())) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
                lines.add("");
            }
            lines.add(CODEX_TABLE);
            lines.add("url = \"" + tomlString(url) + "\"");
            return Edit.write(join(lines, newline));
        }
        int end = lines.size();
        for (int i = header + 1; i < lines.size(); i++) {
            if (isTable(lines.get(i))) {
                end = i;
                break;
            }
        }
        boolean command = false;
        int urlLine = -1;
        String urlValue = null;
        for (int i = header + 1; i < end; i++) {
            if (COMMAND_LINE.matcher(lines.get(i)).find()) {
                command = true;
            }
            Matcher matcher = URL_LINE.matcher(lines.get(i));
            if (matcher.matches()) {
                urlLine = i;
                urlValue = unquote(matcher.group(2));
            }
        }
        if (command) {
            return Edit.unchangedCustom();
        }
        if (urlLine >= 0 && url.equals(urlValue)) {
            return Edit.unchanged();
        }
        String quoted = "url = \"" + tomlString(url) + "\"";
        if (urlLine >= 0) {
            lines.set(urlLine, quoted);
        } else {
            lines.add(header + 1, quoted);
        }
        return Edit.write(join(lines, newline));
    }

    private static void putServer(ObjectNode servers, String url) {
        ObjectNode server = servers.putObject(SERVER_NAME);
        server.put("type", "http");
        server.put("url", url);
    }

    private static boolean isTable(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2;
    }

    private static String unquote(String raw) {
        String token = EditorUserSettings.firstToken(raw);
        if (token.length() >= 2) {
            char open = token.charAt(0);
            char close = token.charAt(token.length() - 1);
            if ((open == '"' && close == '"') || (open == '\'' && close == '\'')) {
                return token.substring(1, token.length() - 1);
            }
        }
        return token;
    }

    private static String tomlString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<String> split(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(List.of(parts));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String join(List<String> lines, String newline) {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join(newline, lines) + newline;
    }
}
