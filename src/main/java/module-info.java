module ca.canada.inspection.insilicopcr {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires org.apache.commons.cli;

    opens ca.canada.inspection.insilicopcr to javafx.fxml;

    exports ca.canada.inspection.insilicopcr;
    exports ca.canada.inspection.commandpcr;
    exports ca.canada.inspection.dispatchpcr;
    exports ca.canada.inspection.pipeline;
    exports ca.canada.inspection.util;
}
