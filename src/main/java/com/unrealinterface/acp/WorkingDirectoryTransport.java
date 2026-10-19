package com.unrealinterface.acp;

import java.nio.file.Path;

import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;

/** Stdio transport that starts the agent with the project as its working directory. */
final class WorkingDirectoryTransport extends StdioAcpClientTransport {

    private final Path workingDirectory;

    WorkingDirectoryTransport(AgentParameters params, Path workingDirectory) {
        super(params);
        this.workingDirectory = workingDirectory;
    }

    @Override
    protected ProcessBuilder getProcessBuilder() {
        ProcessBuilder builder = super.getProcessBuilder();
        builder.directory(workingDirectory.toFile());
        return builder;
    }
}
