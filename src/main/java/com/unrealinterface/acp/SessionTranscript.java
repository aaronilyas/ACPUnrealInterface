package com.unrealinterface.acp;

import java.util.List;

import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AvailableCommandsUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.ContentBlock;
import com.agentclientprotocol.sdk.spec.AcpSchema.CurrentModeUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.Plan;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionInfoUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallContentBlock;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallDiff;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallLocation;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallTerminal;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdateNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.UnknownSessionUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.UsageUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.UserMessageChunk;

/**
 * Turns ACP session updates into text for the output pane. Consecutive message
 * chunks stay on the same stream. Tool calls, plans, and status lines break it.
 */
public final class SessionTranscript {

    private enum Stream {
        NONE, AGENT, THOUGHT, USER
    }

    private Stream stream = Stream.NONE;
    private boolean atLineStart = true;

    public synchronized void reset() {
        stream = Stream.NONE;
        atLineStart = true;
    }

    public synchronized String status(String message) {
        String prefix = atLineStart ? "" : "\n";
        stream = Stream.NONE;
        atLineStart = true;
        return prefix + message + "\n";
    }

    public synchronized String accept(SessionUpdate update) {
        if (update instanceof AgentMessageChunk chunk) {
            return streamText(Stream.AGENT, "", textOf(chunk.content()));
        }
        if (update instanceof AgentThoughtChunk chunk) {
            return streamText(Stream.THOUGHT, "[thought] ", textOf(chunk.content()));
        }
        if (update instanceof UserMessageChunk chunk) {
            return streamText(Stream.USER, "[you] ", textOf(chunk.content()));
        }
        if (update instanceof ToolCall call) {
            return block(toolText(call.toolCallId(), call.title(), call.name(), call.kind(), call.status(),
                    call.content(), call.locations()));
        }
        if (update instanceof ToolCallUpdateNotification call) {
            return block(toolText(call.toolCallId(), call.title(), call.name(), call.kind(), call.status(),
                    call.content(), call.locations()));
        }
        if (update instanceof Plan plan) {
            return block(planText(plan));
        }
        if (update instanceof UsageUpdate usage) {
            return block(usageText(usage));
        }
        if (update instanceof CurrentModeUpdate mode) {
            return block("[status] mode " + mode.currentModeId() + "\n");
        }
        if (update instanceof SessionInfoUpdate info) {
            if (info.title() == null || info.title().isBlank()) {
                return "";
            }
            return block("[status] title " + info.title() + "\n");
        }
        if (update instanceof AvailableCommandsUpdate commands) {
            return block(commandsText(commands));
        }
        if (update instanceof UnknownSessionUpdate unknown) {
            String name = unknown.sessionUpdate() == null ? "unknown" : unknown.sessionUpdate();
            return block("[update] " + name + "\n");
        }
        return block("[update] " + update.getClass().getSimpleName() + "\n");
    }

    private String streamText(Stream next, String label, String text) {
        StringBuilder out = new StringBuilder();
        if (stream != next) {
            if (!atLineStart) {
                out.append('\n');
            }
            out.append(label);
            stream = next;
        }
        out.append(text);
        atLineStart = text.endsWith("\n");
        return out.toString();
    }

    private String block(String text) {
        if (text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (!atLineStart) {
            out.append('\n');
        }
        stream = Stream.NONE;
        out.append(text);
        if (!text.endsWith("\n")) {
            out.append('\n');
        }
        atLineStart = true;
        return out.toString();
    }

    private static String toolText(String id, String title, String name, ToolKind kind, ToolCallStatus status,
            List<? extends ToolCallContent> content, List<ToolCallLocation> locations) {
        StringBuilder out = new StringBuilder("[tool] ");
        if (name != null && !name.isBlank()) {
            out.append(name);
        } else if (id != null) {
            out.append(id);
        }
        if (title != null && !title.isBlank()) {
            out.append(" — ").append(title);
        }
        if (kind != null) {
            out.append(" [").append(kind.value()).append(']');
        }
        if (status != null) {
            out.append(" (").append(status.value()).append(')');
        }
        out.append('\n');
        if (content != null) {
            for (ToolCallContent item : content) {
                appendContent(out, item);
            }
        }
        if (locations != null) {
            for (ToolCallLocation location : locations) {
                out.append("  at ").append(location.path());
                if (location.line() != null) {
                    out.append(':').append(location.line());
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static void appendContent(StringBuilder out, ToolCallContent item) {
        if (item instanceof ToolCallContentBlock block) {
            appendIndented(out, textOf(block.content()));
        } else if (item instanceof ToolCallDiff diff) {
            out.append("  [diff] ").append(diff.path());
            if (diff.oldText() == null) {
                out.append(" (new file)");
            }
            out.append('\n');
        } else if (item instanceof ToolCallTerminal terminal) {
            out.append("  [terminal] ").append(terminal.terminalId()).append('\n');
        }
    }

    private static String planText(Plan plan) {
        StringBuilder out = new StringBuilder("[plan]\n");
        if (plan.entries() != null) {
            for (var entry : plan.entries()) {
                out.append("  - ").append(entry.status().value())
                        .append(" (").append(entry.priority().value()).append(") ")
                        .append(entry.content()).append('\n');
            }
        }
        return out.toString();
    }

    private static String usageText(UsageUpdate usage) {
        StringBuilder out = new StringBuilder("[status] context ")
                .append(usage.used()).append('/').append(usage.size()).append(" tokens");
        if (usage.cost() != null && usage.cost().amount() != null) {
            out.append(", cost ").append(usage.cost().amount()).append(' ').append(usage.cost().currency());
        }
        out.append('\n');
        return out.toString();
    }

    private static String commandsText(AvailableCommandsUpdate commands) {
        StringBuilder out = new StringBuilder("[status] commands:");
        if (commands.availableCommands() != null) {
            for (var command : commands.availableCommands()) {
                out.append(' ').append(command.name());
            }
        }
        out.append('\n');
        return out.toString();
    }

    private static String textOf(ContentBlock content) {
        if (content instanceof TextContent text) {
            String value = text.text() == null ? "" : text.text();
            if (value.length() > 8000) {
                return value.substring(0, 8000) + "…";
            }
            return value;
        }
        if (content == null) {
            return "";
        }
        return "[" + content.getClass().getSimpleName() + "]";
    }

    private static void appendIndented(StringBuilder out, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String clipped = text.length() > 4000 ? text.substring(0, 4000) + "…" : text;
        for (String line : clipped.split("\n", -1)) {
            out.append("  ").append(line).append('\n');
        }
    }
}
