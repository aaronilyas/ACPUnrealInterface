package com.unrealinterface.acp;

/**
 * A session could not be started, or a prompt could not be delivered.
 * {@link Kind} tells the window which failure to explain.
 */
public class AgentStartupException extends Exception {

    public enum Kind {
        /** The agent program is not on PATH, or the given path is not executable. */
        MISSING_BINARY,
        /** The process started, but initialize or session/new did not succeed. */
        HANDSHAKE,
        /** There is no open session, or the project directory is unusable. */
        NOT_READY,
        /** The agent rejected or dropped the prompt. */
        PROMPT
    }

    private final Kind kind;

    public AgentStartupException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AgentStartupException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
