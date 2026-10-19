package com.unrealinterface.unreal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sets {@code bAutoStartServer} in the Model Context Protocol editor-settings section
 * and reads a port or path override when one is already present.
 */
final class EditorUserSettings {

    static final String SECTION = "[/Script/ModelContextProtocolEngine.ModelContextProtocolSettings]";
    static final String AUTO_START_LINE = "bAutoStartServer=True";
    static final String DEFAULT_URL = "http://127.0.0.1:8000/mcp";

    private static final Pattern AUTO_START = Pattern.compile("^(\\s*)bAutoStartServer\\s*=\\s*(.*)$");
    private static final Pattern PORT = Pattern.compile("^\\s*ServerPortNumber\\s*=\\s*(.*)$");
    private static final Pattern URL_PATH = Pattern.compile("^\\s*ServerUrlPath\\s*=\\s*(.*)$");

    record Result(String content, boolean changed, Integer port, String urlPath, String warning) {
    }

    private EditorUserSettings() {
    }

    static Result apply(String existing) {
        String newline = existing != null && existing.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = split(existing);
        boolean changed = false;
        int section = indexOfSection(lines);
        if (section < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
                lines.add("");
            }
            lines.add(SECTION);
            lines.add(AUTO_START_LINE);
            changed = true;
        } else {
            int end = sectionEnd(lines, section);
            boolean found = false;
            for (int i = section + 1; i < end; i++) {
                Matcher matcher = AUTO_START.matcher(lines.get(i));
                if (!matcher.matches()) {
                    continue;
                }
                found = true;
                if (!"true".equalsIgnoreCase(firstToken(matcher.group(2)))) {
                    lines.set(i, AUTO_START_LINE);
                    changed = true;
                }
            }
            if (!found) {
                lines.add(section + 1, AUTO_START_LINE);
                changed = true;
            }
        }
        section = indexOfSection(lines);
        Endpoint endpoint = readEndpoint(lines, section);
        if (!changed) {
            return new Result(existing == null ? join(lines, newline) : existing, false,
                    endpoint.port, endpoint.urlPath, endpoint.warning);
        }
        return new Result(join(lines, newline), true, endpoint.port, endpoint.urlPath, endpoint.warning);
    }

    static String endpoint(Integer port, String urlPath) {
        int value = port == null ? 8000 : port;
        String suffix = urlPath == null || urlPath.isBlank() ? "/mcp" : urlPath.trim();
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return "http://127.0.0.1:" + value + suffix;
    }

    private static Endpoint readEndpoint(List<String> lines, int section) {
        Integer port = null;
        String urlPath = null;
        String warning = null;
        int end = sectionEnd(lines, section);
        for (int i = section + 1; i < end; i++) {
            Matcher portMatcher = PORT.matcher(lines.get(i));
            if (portMatcher.matches()) {
                String token = firstToken(portMatcher.group(1));
                try {
                    int value = Integer.parseInt(token);
                    if (value >= 1 && value <= 65535) {
                        port = value;
                        warning = null;
                    } else {
                        port = null;
                        warning = invalidPortWarning();
                    }
                } catch (NumberFormatException ex) {
                    port = null;
                    warning = invalidPortWarning();
                }
            }
            Matcher pathMatcher = URL_PATH.matcher(lines.get(i));
            if (pathMatcher.matches()) {
                String token = firstToken(pathMatcher.group(1));
                if (!token.isEmpty()) {
                    urlPath = token;
                }
            }
        }
        return new Endpoint(port, urlPath, warning);
    }

    private static String invalidPortWarning() {
        return "ServerPortNumber in EditorPerProjectUserSettings.ini is not a valid port, so the client URL keeps "
                + DEFAULT_URL + ".";
    }

    private static int indexOfSection(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (SECTION.equals(lines.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }

    private static int sectionEnd(List<String> lines, int section) {
        for (int i = section + 1; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
                return i;
            }
        }
        return lines.size();
    }

    private static List<String> split(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
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

    static String firstToken(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int cut = trimmed.length();
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == ';') {
                cut = i;
                break;
            }
        }
        String token = trimmed.substring(0, cut).trim();
        if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
            token = token.substring(1, token.length() - 1);
        }
        return token;
    }

    private record Endpoint(Integer port, String urlPath, String warning) {
    }
}
