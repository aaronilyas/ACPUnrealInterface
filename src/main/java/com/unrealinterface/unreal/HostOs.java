package com.unrealinterface.unreal;

import java.util.Locale;

/** Operating system that will launch Unreal Editor. Picks the Saved/Config directory. */
enum HostOs {
    WINDOWS("WindowsEditor"),
    MAC("MacEditor"),
    LINUX("LinuxEditor");

    private final String editorDirectory;

    HostOs(String editorDirectory) {
        this.editorDirectory = editorDirectory;
    }

    String editorDirectory() {
        return editorDirectory;
    }

    static HostOs current() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MAC;
        }
        return LINUX;
    }
}
