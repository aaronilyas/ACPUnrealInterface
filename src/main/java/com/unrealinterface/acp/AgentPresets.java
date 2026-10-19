package com.unrealinterface.acp;

/**
 * Commands that launch ACP adapters. Claude Code and Codex do not speak ACP
 * themselves; these adapters do, and then drive the installed CLI.
 */
public final class AgentPresets {

    public static final String CLAUDE = "Claude";
    public static final String CODEX = "Codex";
    public static final String CUSTOM = "Custom";

    public static final String CLAUDE_COMMAND = "npx -y @agentclientprotocol/claude-agent-acp";
    public static final String CODEX_COMMAND = "npx -y @agentclientprotocol/codex-acp";

    private AgentPresets() {
    }

    public static String commandFor(String preset) {
        if (CLAUDE.equals(preset)) {
            return CLAUDE_COMMAND;
        }
        if (CODEX.equals(preset)) {
            return CODEX_COMMAND;
        }
        return "";
    }
}
