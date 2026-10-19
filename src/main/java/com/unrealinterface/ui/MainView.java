package com.unrealinterface.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.unrealinterface.acp.AgentPresets;
import com.unrealinterface.acp.AgentSession;
import com.unrealinterface.acp.AgentStartupException;
import com.unrealinterface.acp.CommandLine;
import com.unrealinterface.acp.PermissionChoices;
import com.unrealinterface.acp.SessionTranscript;
import com.unrealinterface.skills.SkillInstallPolicy;
import com.unrealinterface.skills.SkillsInstaller;

import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

public final class MainView {

    private final Stage stage;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "unreal-interface-session");
        thread.setDaemon(true);
        return thread;
    });
    private static final int MAX_OUTPUT_CHARS = 200_000;

    private final SessionTranscript transcript = new SessionTranscript();
    private final OutputBuffer outputBuffer = new OutputBuffer(MAX_OUTPUT_CHARS);
    private final AtomicBoolean outputFlushScheduled = new AtomicBoolean();
    private final AtomicBoolean autoApprove = new AtomicBoolean(false);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final SkillsInstaller skillsInstaller = new SkillsInstaller();
    private final SkillInstallPolicy skillInstalls = new SkillInstallPolicy();
    private final AgentSession session;
    private final TextField projectField = new TextField();
    private final TextField commandField = new TextField();
    private final ComboBox<String> agentCombo = new ComboBox<>();
    private final TextArea output = new TextArea();
    private final TextArea prompt = new TextArea();
    private final Label sessionStatus = new Label("No session");
    private final Button send = new Button("Send");
    private final Button cancel = new Button("Cancel");
    private final Button newSession = new Button("New session");
    private final Button browse = new Button("Browse");
    private final Button clear = new Button("Clear");
    private boolean busy;
    private boolean adjustingCommand;
    private volatile boolean closing;
    private Path activeProject;
    private List<String> activeCommand = List.of();

    public MainView(Stage stage) {
        this.stage = stage;
        this.session = new AgentSession(new AgentSession.SessionListener() {
            @Override
            public void onUpdate(com.agentclientprotocol.sdk.spec.AcpSchema.SessionUpdate update) {
                publishUpdate(update);
            }

            @Override
            public void onStatus(String message) {
                showStatus(message);
            }

            @Override
            public void onStderr(String line) {
                showStatus("[stderr] " + line);
            }
        }, this::choosePermission);
    }

    public void show() {
        stage.setTitle("UnrealInterface");
        stage.setScene(new Scene(layout(), 1040, 720));
        stage.setMinWidth(860);
        stage.setMinHeight(560);
        var stylesheet = MainView.class.getResource("/unreal-interface.css");
        if (stylesheet != null) {
            stage.getScene().getStylesheets().add(stylesheet.toExternalForm());
        }
        stage.setOnCloseRequest(event -> shutdown());
        stage.show();
        showStatus("Choose a project directory, then send a prompt. Ctrl+Enter sends.");
    }

    public void shutdown() {
        closing = true;
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        // close() waits for the agent process (up to about ten seconds). Running it on the
        // JavaFX thread freezes the window, so a non-daemon thread reaps the process after
        // the window is gone and keeps the JVM alive until that finishes.
        Thread thread = new Thread(() -> {
            try {
                session.close();
            } finally {
                worker.shutdownNow();
            }
        }, "unreal-interface-shutdown");
        thread.setDaemon(false);
        thread.start();
    }

    private BorderPane layout() {
        projectField.setPromptText("Project directory");
        projectField.setEditable(true);
        commandField.setText(AgentPresets.CLAUDE_COMMAND);
        agentCombo.getItems().addAll(AgentPresets.CLAUDE, AgentPresets.CODEX, AgentPresets.CUSTOM);
        agentCombo.setValue(AgentPresets.CLAUDE);
        agentCombo.valueProperty().addListener((obs, previous, value) -> applyPreset(value));
        commandField.textProperty().addListener((obs, previous, value) -> markCustomIfEdited(value));

        projectField.setOnAction(event -> requestInstall(SkillInstallPolicy.Trigger.AUTO));
        projectField.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused) {
                requestInstall(SkillInstallPolicy.Trigger.AUTO);
            }
        });
        browse.setOnAction(event -> chooseProject());
        newSession.setOnAction(event -> beginSession());
        clear.setOnAction(event -> clearOutput());
        send.setOnAction(event -> sendPrompt());
        cancel.setOnAction(event -> requestCancel());
        cancel.setDisable(true);

        CheckBox approveBox = new CheckBox("Auto-approve permissions");
        approveBox.setSelected(false);
        approveBox.selectedProperty().addListener((obs, previous, value) -> autoApprove.set(value));

        sessionStatus.getStyleClass().add("status");
        output.setEditable(false);
        output.setWrapText(true);
        output.getStyleClass().add("output");
        prompt.setPromptText("Ask the agent to work in this project");
        prompt.setPrefRowCount(4);
        prompt.setWrapText(true);
        prompt.getStyleClass().add("prompt");
        prompt.textProperty().addListener((obs, previous, value) -> refreshSend());
        prompt.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.ENTER) {
                event.consume();
                send.fire();
            }
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        ColumnConstraints grow = new ColumnConstraints();
        grow.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(new ColumnConstraints(), new ColumnConstraints(), grow, new ColumnConstraints());
        form.add(new Label("Project"), 0, 0);
        form.add(projectField, 1, 0, 2, 1);
        form.add(browse, 3, 0);
        form.add(new Label("Agent"), 0, 1);
        form.add(agentCombo, 1, 1);
        form.add(commandField, 2, 1, 2, 1);
        GridPane.setHgrow(projectField, Priority.ALWAYS);
        GridPane.setHgrow(commandField, Priority.ALWAYS);

        HBox actions = new HBox(8, newSession, clear, approveBox, sessionStatus);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(8, form, actions);

        HBox promptBar = new HBox(8, send, cancel);
        promptBar.setAlignment(Pos.CENTER_RIGHT);
        VBox bottom = new VBox(8, prompt, promptBar);

        BorderPane root = new BorderPane(output, top, null, bottom, null);
        root.setPadding(new Insets(12));
        BorderPane.setMargin(output, new Insets(10, 0, 10, 0));
        refreshSend();
        return root;
    }

    private void applyPreset(String preset) {
        if (adjustingCommand || preset == null || AgentPresets.CUSTOM.equals(preset)) {
            return;
        }
        String command = AgentPresets.commandFor(preset);
        if (!command.isBlank() && !command.equals(commandField.getText())) {
            adjustingCommand = true;
            commandField.setText(command);
            adjustingCommand = false;
        }
    }

    private void markCustomIfEdited(String value) {
        if (adjustingCommand) {
            return;
        }
        if (!AgentPresets.CLAUDE_COMMAND.equals(value) && !AgentPresets.CODEX_COMMAND.equals(value)
                && !AgentPresets.CUSTOM.equals(agentCombo.getValue())) {
            adjustingCommand = true;
            agentCombo.setValue(AgentPresets.CUSTOM);
            adjustingCommand = false;
        }
    }

    private void chooseProject() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open project");
        Path current = readProjectPath();
        if (current != null && Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }
        File selected = chooser.showDialog(stage);
        if (selected == null) {
            return;
        }
        projectField.setText(selected.getAbsolutePath());
        requestInstall(SkillInstallPolicy.Trigger.EXPLICIT);
        refreshSend();
    }

    private void requestInstall(SkillInstallPolicy.Trigger trigger) {
        Path project = readProjectPath();
        if (project == null) {
            return;
        }
        SkillInstallPolicy.Decision decision;
        try {
            decision = skillInstalls.evaluate(project, trigger);
        } catch (IOException e) {
            showStatus("Could not install the skills bundle: " + e.getMessage());
            return;
        }
        if (decision.outcome() == SkillInstallPolicy.Outcome.INSTALL) {
            installSkills(decision.directory());
        } else if (decision.outcome() == SkillInstallPolicy.Outcome.FORBIDDEN && decision.report()) {
            showStatus(decision.message());
        }
    }

    private void installSkills(Path project) {
        worker.execute(() -> {
            try {
                var result = skillsInstaller.install(project);
                showStatus(result.message());
            } catch (IOException e) {
                skillInstalls.release(project);
                showStatus("Could not install the skills bundle: " + e.getMessage());
            }
        });
    }

    private void sendPrompt() {
        String text = prompt.getText() == null ? "" : prompt.getText().trim();
        if (text.isBlank() || busy) {
            return;
        }
        Path project;
        List<String> command;
        try {
            project = requireProject();
            command = CommandLine.tokenize(commandField.getText());
        } catch (RuntimeException | AgentStartupException e) {
            showFailure(e.getMessage());
            return;
        }
        prompt.clear();
        showStatus("[you] " + text);
        setBusy(true);
        worker.execute(() -> {
            try {
                ensureSession(project, command, false);
                var response = session.prompt(text);
                String reason = response.stopReason() == null ? "done" : response.stopReason().value();
                Platform.runLater(() -> {
                    showStatus("[done] " + reason);
                    setBusy(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showFailure(e.getMessage() == null ? e.toString() : e.getMessage());
                    if (prompt.getText() == null || prompt.getText().isBlank()) {
                        prompt.setText(text);
                    }
                    setBusy(false);
                });
            }
        });
    }

    private void beginSession() {
        if (busy) {
            return;
        }
        Path project;
        List<String> command;
        try {
            project = requireProject();
            command = CommandLine.tokenize(commandField.getText());
        } catch (RuntimeException | AgentStartupException e) {
            showFailure(e.getMessage());
            return;
        }
        setBusy(true);
        worker.execute(() -> {
            try {
                ensureSession(project, command, true);
                Platform.runLater(() -> setBusy(false));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showFailure(e.getMessage() == null ? e.toString() : e.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private void ensureSession(Path project, List<String> command, boolean restart) throws Exception {
        boolean sameSession = session.isReady() && project.equals(activeProject) && command.equals(activeCommand);
        if (sameSession && !restart) {
            return;
        }
        installForSession(project);
        session.start(project, command, Map.of());
        activeProject = project;
        activeCommand = List.copyOf(command);
        String label = session.agentLabel() + " · " + session.sessionId();
        Platform.runLater(() -> sessionStatus.setText(label));
    }

    private void installForSession(Path project) throws IOException {
        SkillInstallPolicy.Decision decision = skillInstalls.evaluate(project, SkillInstallPolicy.Trigger.EXPLICIT);
        if (decision.outcome() == SkillInstallPolicy.Outcome.INSTALL) {
            try {
                showStatus(skillsInstaller.install(decision.directory()).message());
            } catch (IOException e) {
                skillInstalls.release(decision.directory());
                throw e;
            }
        } else if (decision.outcome() == SkillInstallPolicy.Outcome.FORBIDDEN && decision.report()) {
            showStatus(decision.message());
        }
    }

    private Path requireProject() throws AgentStartupException {
        Path path = readProjectPath();
        if (path == null) {
            throw new AgentStartupException(AgentStartupException.Kind.NOT_READY, "Choose a project directory");
        }
        if (!Files.isDirectory(path)) {
            throw new AgentStartupException(AgentStartupException.Kind.NOT_READY,
                    "Project directory does not exist: " + path);
        }
        return path;
    }

    private Path readProjectPath() {
        String text = projectField.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return Path.of(text.trim()).toAbsolutePath().normalize();
    }

    private void publishUpdate(com.agentclientprotocol.sdk.spec.AcpSchema.SessionUpdate update) {
        synchronized (transcript) {
            outputBuffer.append(transcript.accept(update));
        }
        scheduleOutputFlush();
    }

    private void showStatus(String message) {
        synchronized (transcript) {
            outputBuffer.append(transcript.status(message));
        }
        scheduleOutputFlush();
    }

    private void clearOutput() {
        synchronized (transcript) {
            outputBuffer.clear();
            transcript.reset();
        }
        output.clear();
    }

    private void scheduleOutputFlush() {
        if (outputFlushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flushOutput);
        }
    }

    private void flushOutput() {
        String snapshot;
        long version;
        synchronized (outputBuffer) {
            snapshot = outputBuffer.text();
            version = outputBuffer.version();
            outputFlushScheduled.set(false);
        }
        if (version != outputBuffer.version()) {
            scheduleOutputFlush();
            return;
        }
        output.setText(snapshot);
        output.positionCaret(snapshot.length());
        if (version != outputBuffer.version()) {
            scheduleOutputFlush();
        }
    }

    private void requestCancel() {
        // prompt() occupies the session worker until the turn ends, and cancel() blocks
        // until the notification is written. A separate thread keeps the JavaFX thread free.
        Thread thread = new Thread(session::cancel, "unreal-interface-cancel");
        thread.setDaemon(true);
        thread.start();
    }

    private void showFailure(String message) {
        showStatus(message);
        if (closing) {
            return;
        }
        String dialogText = message.length() > 700 ? message.substring(0, 700) + "…" : message;
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle("UnrealInterface");
        alert.setHeaderText("Agent session failed");
        alert.setContentText(dialogText);
        alert.show();
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        cancel.setDisable(!busy);
        newSession.setDisable(busy);
        browse.setDisable(busy);
        agentCombo.setDisable(busy);
        commandField.setDisable(busy);
        projectField.setDisable(busy);
        clear.setDisable(busy);
        refreshSend();
    }

    private void refreshSend() {
        boolean hasProject = projectField.getText() != null && !projectField.getText().isBlank();
        boolean hasPrompt = prompt.getText() != null && !prompt.getText().isBlank();
        send.setDisable(busy || !hasProject || !hasPrompt);
    }

    private String choosePermission(String summary, List<PermissionOption> options) throws InterruptedException {
        if (autoApprove.get()) {
            String allowed = PermissionChoices.allowOnce(options);
            if (allowed != null) {
                return allowed;
            }
        }
        if (Platform.isFxApplicationThread()) {
            return askOnFxThread(summary, options);
        }
        AtomicReference<String> chosen = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                chosen.set(askOnFxThread(summary, options));
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.MINUTES)) {
            return null;
        }
        return chosen.get();
    }

    private String askOnFxThread(String summary, List<PermissionOption> options) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Agent permission");
        dialog.setHeaderText("The agent is asking for permission");
        VBox box = new VBox(8);
        Label label = new Label(summary);
        label.setWrapText(true);
        label.setMaxWidth(420);
        box.getChildren().add(label);
        AtomicReference<String> result = new AtomicReference<>();
        if (options != null) {
            for (PermissionOption option : options) {
                String kind = option.kind() == null ? "" : option.kind().value();
                String caption = option.name() + (kind.isBlank() ? "" : " (" + kind + ")");
                Button button = new Button(caption);
                button.setMaxWidth(Double.MAX_VALUE);
                button.setOnAction(event -> {
                    result.set(option.optionId());
                    dialog.setResult(option.optionId());
                    dialog.close();
                });
                box.getChildren().add(button);
            }
        }
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().setContent(box);
        dialog.setResultConverter(button -> result.get());
        dialog.showAndWait();
        return result.get();
    }
}
