package com.unrealinterface.ui;

/**
 * Capped text for the streaming output pane. Appends are safe from the agent
 * threads; the window copies {@link #text()} onto the JavaFX thread.
 */
public final class OutputBuffer {

    static final String TRUNCATION_NOTICE = "[status] earlier output was truncated\n";

    private final int maxChars;
    private final StringBuilder text = new StringBuilder();
    private boolean truncated;
    private long version;

    public OutputBuffer(int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be >= 1");
        }
        this.maxChars = maxChars;
    }

    public synchronized void append(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        text.append(chunk);
        if (text.length() > maxChars) {
            text.delete(0, text.length() - maxChars);
            truncated = true;
        }
        version++;
    }

    public synchronized void clear() {
        if (text.length() == 0 && !truncated) {
            return;
        }
        text.setLength(0);
        truncated = false;
        version++;
    }

    public synchronized long version() {
        return version;
    }

    public synchronized String text() {
        if (!truncated) {
            return text.toString();
        }
        return TRUNCATION_NOTICE + text;
    }
}
