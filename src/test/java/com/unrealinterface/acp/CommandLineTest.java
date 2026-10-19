package com.unrealinterface.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CommandLineTest {

    @Test
    void splitsWordsAndQuotes() {
        assertEquals(List.of("npx", "-y", "@agentclientprotocol/claude-agent-acp"),
                CommandLine.tokenize("npx -y @agentclientprotocol/claude-agent-acp"));
        assertEquals(List.of("echo", "hello world"), CommandLine.tokenize("echo \"hello world\""));
        assertEquals(List.of("tool", "a b"), CommandLine.tokenize("tool 'a b'"));
    }

    @Test
    void rejectsEmptyAndUnclosedQuotes() {
        assertThrows(IllegalArgumentException.class, () -> CommandLine.tokenize("   "));
        assertThrows(IllegalArgumentException.class, () -> CommandLine.tokenize("echo \"unterminated"));
    }

    @Test
    void findsProgramsOnPathAndRejectsMissingNames() {
        assertTrue(CommandLine.isExecutable("/bin/true"));
        assertFalse(CommandLine.isExecutable("unreal-interface-missing-agent-binary"));
    }
}
