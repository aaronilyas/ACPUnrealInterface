package com.unrealinterface.unreal;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class JsonTexts {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonTexts() {
    }

    static String stripBom(String text) {
        if (text != null && text.startsWith("\uFEFF")) {
            return text.substring(1);
        }
        return text;
    }

    static String pretty(JsonNode node) throws IOException {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        String text;
        try {
            text = MAPPER.writer(printer).writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        if (!text.endsWith("\n")) {
            text = text + "\n";
        }
        return text;
    }
}
