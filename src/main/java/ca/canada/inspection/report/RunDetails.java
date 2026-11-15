package ca.canada.inspection.report;

import java.util.List;
import java.util.Objects;

/**
 * What the HTML report says about its run: when, by which version, and with what.
 *
 * @param date     when the run was made, as shown
 * @param version  the insilicoPCR version that made it
 * @param settings the run's paths and settings, listed in this order under "Run details"
 */
public record RunDetails(String date, String version, List<Setting> settings) {

    /** One entry of "Run details". */
    public record Setting(String label, String value) {
        public Setting {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
        }
    }

    public RunDetails {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(version, "version");
        settings = List.copyOf(settings);
    }
}
