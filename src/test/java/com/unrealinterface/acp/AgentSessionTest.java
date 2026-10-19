package com.unrealinterface.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.StopReason;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;

@Timeout(30)
class AgentSessionTest {

    @TempDir
    Path project;

    @Test
    void startsASessionAndStreamsAPrompt() throws Exception {
        Path notes = project.resolve("notes.txt");
        Files.writeString(notes, "HELLO");
        List<SessionUpdate> updates = new CopyOnWriteArrayList<>();
        List<String> stderr = new CopyOnWriteArrayList<>();
        AgentSession session = new AgentSession(listener(updates, stderr), (summary, options) -> null,
                Duration.ofSeconds(8), Duration.ofSeconds(8));
        try {
            session.start(project, List.of(python(), "-u", fakeAgentScript().toString()),
                    Map.of("FAKE_AGENT_READ", notes.toAbsolutePath().toString()));
            assertEquals("sess-1", session.sessionId());
            assertEquals("fake-agent 0.0.1", session.agentLabel());
            var response = session.prompt("Read the notes");
            assertEquals(StopReason.END_TURN, response.stopReason());
        } finally {
            session.close();
        }

        assertTrue(stderr.stream().anyMatch(line -> line.contains("fake-agent ready")), stderr::toString);
        String message = updates.stream()
                .filter(AgentMessageChunk.class::isInstance)
                .map(AgentMessageChunk.class::cast)
                .map(chunk -> ((TextContent) chunk.content()).text())
                .findFirst()
                .orElse("");
        assertEquals("read:HELLO", message);
        assertTrue(updates.stream().anyMatch(update -> update instanceof ToolCall tool
                && "echo".equals(tool.name())
                && "completed".equals(tool.status().value())));
    }

    @Test
    void reportsAMissingAgentBinary() {
        AgentSession session = new AgentSession(listener(new ArrayList<>(), new ArrayList<>()),
                (summary, options) -> null, Duration.ofSeconds(2), Duration.ofSeconds(2));
        try {
            AgentStartupException error = assertThrows(AgentStartupException.class,
                    () -> session.start(project, List.of("unreal-interface-missing-agent-binary"), Map.of()));
            assertEquals(AgentStartupException.Kind.MISSING_BINARY, error.kind());
            assertTrue(error.getMessage().contains("not found"), error.getMessage());
        } finally {
            session.close();
        }
    }

    @Test
    void reportsAFailedHandshake() {
        AgentSession session = new AgentSession(listener(new ArrayList<>(), new ArrayList<>()),
                (summary, options) -> null, Duration.ofSeconds(5), Duration.ofSeconds(5));
        try {
            AgentStartupException error = assertThrows(AgentStartupException.class,
                    () -> session.start(project, List.of("/bin/true"), Map.of()));
            assertEquals(AgentStartupException.Kind.HANDSHAKE, error.kind());
            assertTrue(error.getMessage().toLowerCase().contains("handshake"), error.getMessage());
        } finally {
            session.close();
        }
    }

    private static AgentSession.SessionListener listener(List<SessionUpdate> updates, List<String> stderr) {
        return new AgentSession.SessionListener() {
            @Override
            public void onUpdate(SessionUpdate update) {
                updates.add(update);
            }

            @Override
            public void onStatus(String message) {
            }

            @Override
            public void onStderr(String line) {
                stderr.add(line);
            }
        };
    }

    private static Path fakeAgentScript() throws URISyntaxException {
        var resource = AgentSessionTest.class.getResource("/fake_agent.py");
        if (resource == null) {
            throw new IllegalStateException("fake_agent.py is not on the test classpath");
        }
        return Path.of(resource.toURI());
    }

    private static String python() {
        if (CommandLine.isExecutable("python3")) {
            return "python3";
        }
        if (CommandLine.isExecutable("python")) {
            return "python";
        }
        throw new IllegalStateException("python3 is required to run the ACP session test");
    }
}
