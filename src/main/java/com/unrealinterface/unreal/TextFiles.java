package com.unrealinterface.unreal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes a text file only when the bytes would change, via a temp file in the same directory. */
final class TextFiles {

    private TextFiles() {
    }

    static boolean writeIfChanged(Path file, String content) throws IOException {
        if (Files.isRegularFile(file)) {
            if (Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
                return false;
            }
        } else if (Files.exists(file)) {
            throw new IOException(file.getFileName() + " is not a file");
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        } else {
            parent = Path.of(".");
        }
        Path tmp = Files.createTempFile(parent, ".unreal-interface-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
