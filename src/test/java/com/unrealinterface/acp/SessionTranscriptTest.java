package com.unrealinterface.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.Plan;
import com.agentclientprotocol.sdk.spec.AcpSchema.PlanEntry;
import com.agentclientprotocol.sdk.spec.AcpSchema.PlanEntryPriority;
import com.agentclientprotocol.sdk.spec.AcpSchema.PlanEntryStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolKind;

class SessionTranscriptTest {

    @Test
    void concatenatesAgentChunksUntilAToolCall() {
        SessionTranscript transcript = new SessionTranscript();
        assertEquals("Hello", transcript.accept(new AgentMessageChunk(new TextContent("Hello"))));
        assertEquals(" world", transcript.accept(new AgentMessageChunk(new TextContent(" world"))));

        String tool = transcript.accept(new ToolCall(null, "call-1", "Reading notes", "read_file",
                ToolKind.READ, ToolCallStatus.PENDING, null, null, null, null, null));
        assertTrue(tool.startsWith("\n"), tool);
        assertTrue(tool.contains("read_file — Reading notes [read] (pending)"), tool);
    }

    @Test
    void labelsThoughtsAndPlans() {
        SessionTranscript transcript = new SessionTranscript();
        assertEquals("[thought] Looking", transcript.accept(new AgentThoughtChunk(new TextContent("Looking"))));
        String plan = transcript.accept(new Plan(List.of(
                new PlanEntry("Check syntax", PlanEntryPriority.HIGH, PlanEntryStatus.PENDING))));
        assertTrue(plan.contains("[plan]"), plan);
        assertTrue(plan.contains("pending (high) Check syntax"), plan);
    }

    @Test
    void statusBreaksTheMessageStream() {
        SessionTranscript transcript = new SessionTranscript();
        transcript.accept(new AgentMessageChunk(new TextContent("Hello")));
        assertEquals("\nSession sess-1\n", transcript.status("Session sess-1"));
        assertEquals("Again", transcript.accept(new AgentMessageChunk(new TextContent("Again"))));
    }
}
