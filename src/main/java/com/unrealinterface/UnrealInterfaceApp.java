package com.unrealinterface;

import com.unrealinterface.ui.MainView;

import javafx.application.Application;
import javafx.stage.Stage;

public final class UnrealInterfaceApp extends Application {

    private MainView view;

    @Override
    public void start(Stage stage) {
        view = new MainView(stage);
        view.show();
    }

    @Override
    public void stop() {
        if (view != null) {
            view.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
