module ca.canada.inspection.insilicopcr {
    requires javafx.controls;
    requires java.desktop;

    requires org.apache.commons.cli;

    exports ca.canada.inspection.insilicopcr;
    exports ca.canada.inspection.commandpcr;
    exports ca.canada.inspection.dispatchpcr;
    exports ca.canada.inspection.pcr;
    exports ca.canada.inspection.pipeline;
    exports ca.canada.inspection.util;
}
