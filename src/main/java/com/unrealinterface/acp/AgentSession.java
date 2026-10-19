package com.unrealinterface.acp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.spec.AcpSchema.CancelNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.Implementation;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionCancelled;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileResponse;

/**
 * One ACP stdio session: start the agent, {@code initialize}, {@code session/new},
 * then {@code session/prompt}. The window only displays what this class reports.
 */
public final class AgentSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);

    /** One stderr line kept for the pane and for handshake errors. */
    private static final int MAX_STDERR_LINE = 2_000;

    /** Receives session traffic. Callers marshal to their own thread. */
    public interface SessionListener {
        void onUpdate(SessionUpdate update);

        void onStatus(String message);

        void onStderr(String line);
    }

    /** Answers {@code session/request_permission}. A null option id cancels the request. */
    public interface PermissionChooser {
        String choose(String summary, List<PermissionOption> options) throws InterruptedException;
    }

    private final SessionListener listener;
    private final PermissionChooser chooser;
    private final Duration requestTimeout;
    private final Duration promptTimeout;
    private final Object lock = new Object();
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private AcpSyncClient client;
    private String sessionId;
    private String agentLabel = "";
    private volatile java.nio.file.Path projectDirectory;
    private boolean closed;

    public AgentSession(SessionListener listener, PermissionChooser chooser) {
        this(listener, chooser, Duration.ofMinutes(10), Duration.ofHours(6));
    }

    public AgentSession(SessionListener listener, PermissionChooser chooser, Duration requestTimeout,
            Duration promptTimeout) {
        this.listener = listener;
        this.chooser = chooser;
        this.requestTimeout = requestTimeout;
        this.promptTimeout = promptTimeout;
    }

    public boolean isReady() {
        synchronized (lock) {
            return client != null && sessionId != null && !closed;
        }
    }

    public String sessionId() {
        synchronized (lock) {
            return sessionId;
        }
    }

    public String agentLabel() {
        synchronized (lock) {
            return agentLabel;
        }
    }

    public void start(java.nio.file.Path projectDirectory, List<String> command, Map<String, String> environment)
            throws AgentStartupException {
        if (projectDirectory == null || !java.nio.file.Files.isDirectory(projectDirectory)) {
            throw new AgentStartupException(AgentStartupException.Kind.NOT_READY,
                    "Project directory does not exist: " + projectDirectory);
        }
        if (command == null || command.isEmpty()) {
            throw new AgentStartupException(AgentStartupException.Kind.NOT_READY, "Agent command is empty");
        }
        String program = command.get(0);
        String resolvedProgram = CommandLine.resolveExecutable(program);
        if (resolvedProgram == null) {
            throw new AgentStartupException(AgentStartupException.Kind.MISSING_BINARY,
                    "Could not start the agent. Program not found on PATH: " + program
                            + ". Install it, or set a custom command that launches an ACP agent.");
        }
        List<String> argv = new ArrayList<>(command);
        argv.set(0, resolvedProgram);
        synchronized (lock) {
            if (closed) {
                throw new AgentStartupException(AgentStartupException.Kind.NOT_READY, "Session is closed");
            }
        }

        AcpSyncClient previous = detachClient();
        if (previous != null) {
            previous.close();
        }
        clearStderr();
        this.projectDirectory = projectDirectory.toAbsolutePath().normalize();
        listener.onStatus("Starting " + String.join(" ", command));

        AgentParameters.Builder params = AgentParameters.builder(resolvedProgram);
        if (argv.size() > 1) {
            params.args(argv.subList(1, argv.size()));
        }
        if (environment != null && !environment.isEmpty()) {
            params.env(environment);
        }
        WorkingDirectoryTransport transport = new WorkingDirectoryTransport(params.build(), this.projectDirectory);
        transport.setStdErrorHandler(this::onStderr);

        AcpSyncClient created;
        try {
            created = AcpClient.sync(transport)
                    .requestTimeout(requestTimeout)
                    .promptTimeout(promptTimeout)
                    .clientInfo(new Implementation("unreal-interface", "1.0.0", "UnrealInterface"))
                    .readTextFileHandler(request -> {
                        listener.onStatus("[file] read " + request.path());
                        try {
                            return new ReadTextFileResponse(ProjectFiles.read(this.projectDirectory, request));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .writeTextFileHandler(request -> {
                        listener.onStatus("[file] write " + request.path());
                        try {
                            ProjectFiles.write(this.projectDirectory, request);
                            return new WriteTextFileResponse();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .requestPermissionHandler(request -> {
                        String summary = PermissionChoices.summary(request);
                        listener.onStatus("Permission requested: " + summary);
                        String optionId;
                        try {
                            optionId = chooser.choose(summary, request.options());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return new RequestPermissionResponse(new PermissionCancelled());
                        }
                        if (optionId == null) {
                            listener.onStatus("Permission cancelled");
                            return new RequestPermissionResponse(new PermissionCancelled());
                        }
                        listener.onStatus("Permission selected: " + optionId);
                        return new RequestPermissionResponse(new PermissionSelected(optionId));
                    })
                    .sessionUpdateHandler(notification -> {
                        if (notification.update() != null) {
                            listener.onUpdate(notification.update());
                        }
                    })
                    .build();
        } catch (RuntimeException e) {
            throw failure(AgentStartupException.Kind.HANDSHAKE, "ACP handshake failed. ", e);
        }

        try {
            var initialized = created.initialize();
            String name = "agent";
            String version = "";
            if (initialized.agentInfo() != null) {
                if (initialized.agentInfo().name() != null) {
                    name = initialized.agentInfo().name();
                }
                if (initialized.agentInfo().version() != null) {
                    version = initialized.agentInfo().version();
                }
            }
            var opened = created.newSession(new NewSessionRequest(this.projectDirectory.toString()));
            synchronized (lock) {
                if (closed) {
                    created.close();
                    throw new AgentStartupException(AgentStartupException.Kind.NOT_READY,
                            "Session closed during startup");
                }
                client = created;
                sessionId = opened.sessionId();
                agentLabel = version.isBlank() ? name : name + " " + version;
            }
            listener.onStatus("Connected to " + agentLabel());
            listener.onStatus("Session " + opened.sessionId());
        } catch (AgentStartupException e) {
            throw e;
        } catch (RuntimeException e) {
            created.close();
            throw failure(AgentStartupException.Kind.HANDSHAKE, "ACP handshake failed. ", e);
        }
    }

    public PromptResponse prompt(String text) throws AgentStartupException {
        if (text == null || text.isBlank()) {
            throw new AgentStartupException(AgentStartupException.Kind.PROMPT, "Prompt is empty");
        }
        AcpSyncClient current;
        String id;
        synchronized (lock) {
            if (closed || client == null || sessionId == null) {
                throw new AgentStartupException(AgentStartupException.Kind.NOT_READY,
                        "Start a session before sending a prompt");
            }
            current = client;
            id = sessionId;
        }
        try {
            return current.prompt(PromptRequest.text(id, text));
        } catch (RuntimeException e) {
            throw failure(AgentStartupException.Kind.PROMPT, "The agent failed while handling the prompt. ", e);
        }
    }

    public void cancel() {
        AcpSyncClient current;
        String id;
        synchronized (lock) {
            current = client;
            id = sessionId;
        }
        if (current == null || id == null) {
            return;
        }
        try {
            current.cancel(new CancelNotification(id));
            listener.onStatus("Cancel requested");
        } catch (RuntimeException e) {
            log.warn("session/cancel failed: {}", e.toString());
            listener.onStatus("Cancel failed: " + describe(e));
        }
    }

    @Override
    public void close() {
        AcpSyncClient current = detachClient();
        synchronized (lock) {
            closed = true;
        }
        if (current != null) {
            current.close();
        }
    }

    private AcpSyncClient detachClient() {
        synchronized (lock) {
            AcpSyncClient current = client;
            client = null;
            sessionId = null;
            return current;
        }
    }

    private void onStderr(String line) {
        if (line != null && line.length() > MAX_STDERR_LINE) {
            line = line.substring(0, MAX_STDERR_LINE) + "…";
        }
        synchronized (stderrTail) {
            stderrTail.addLast(line);
            while (stderrTail.size() > 40) {
                stderrTail.removeFirst();
            }
        }
        listener.onStderr(line);
    }

    private void clearStderr() {
        synchronized (stderrTail) {
            stderrTail.clear();
        }
    }

    private String stderrTail() {
        synchronized (stderrTail) {
            return String.join(System.lineSeparator(), stderrTail);
        }
    }

    private AgentStartupException failure(AgentStartupException.Kind fallback, String prefix, Throwable error) {
        AgentStartupException.Kind kind = isMissingBinary(error)
                ? AgentStartupException.Kind.MISSING_BINARY
                : fallback;
        StringBuilder message = new StringBuilder(prefix).append(describe(error));
        String tail = stderrTail();
        if (!tail.isBlank()) {
            message.append(System.lineSeparator()).append("Agent error output:").append(System.lineSeparator())
                    .append(tail);
        }
        return new AgentStartupException(kind, message.toString(), error);
    }

    private static boolean isMissingBinary(Throwable error) {
        Throwable current = error;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            if (current instanceof IOException) {
                String message = String.valueOf(current.getMessage()).toLowerCase();
                if (message.contains("no such file") || message.contains("error=2") || message.contains("not found")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            String message = current.getMessage();
            if (message != null && !message.isBlank() && text.indexOf(message.trim()) < 0) {
                if (text.length() > 0) {
                    text.append(": ");
                }
                text.append(message.trim());
            }
            current = current.getCause();
        }
        return text.length() == 0 ? error.toString() : text.toString();
    }
}
