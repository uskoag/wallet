package uskoag.wallet.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

/** A yes/no box, keyboard-answerable, with the negative as the cancel button so Esc is always safe. */
public final class Confirm {

    private Confirm() {
    }

    public static boolean ask(String title, String message, String yes, String no) {
        var yesButton = new ButtonType(yes, ButtonBar.ButtonData.OK_DONE);
        var noButton = new ButtonType(no, ButtonBar.ButtonData.CANCEL_CLOSE);
        var alert = new Alert(Alert.AlertType.CONFIRMATION, message, yesButton, noButton);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.getDialogPane().setMinWidth(480);
        alert.initModality(javafx.stage.Modality.NONE);
        ((javafx.stage.Stage) alert.getDialogPane().getScene().getWindow()).setAlwaysOnTop(true);
        return alert.showAndWait().filter(b -> b == yesButton).isPresent();
    }
}
