package com.unrealinterface.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OutputBufferTest {

    @Test
    void keepsTheNewestTextAndMarksTruncation() {
        OutputBuffer buffer = new OutputBuffer(8);
        buffer.append("12345");
        assertEquals("12345", buffer.text());
        buffer.append("67890");
        assertEquals(OutputBuffer.TRUNCATION_NOTICE + "34567890", buffer.text());
        buffer.clear();
        assertEquals("", buffer.text());
    }

    @Test
    void ignoresEmptyChunks() {
        OutputBuffer buffer = new OutputBuffer(8);
        buffer.append("");
        buffer.append(null);
        assertEquals("", buffer.text());
        assertEquals(0, buffer.version());
    }
}
