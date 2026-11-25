package ca.canada.inspection.pcr;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnealTempsTest {

    private static Map<String, String> headers(String... idAndDescription) {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (int i = 0; i < idAndDescription.length; i += 2) {
            descriptions.put(idAndDescription[i], idAndDescription[i + 1]);
        }
        return descriptions;
    }

    @Test
    void anAssayDeclaresItsTemperatureOnAnyOfItsPrimers() {
        AnnealTemps temps = AnnealTemps.fromHeaders(headers(
                "a-F", "size=265 ta=60", "a-R", "TA=60", "b-F", "", "b-R", "ta=63.5", "c-F", "note"), MixGroups.NONE);
        assertEquals(Map.of("a", 60.0, "b", 63.5), temps.byAssay());
        assertEquals(60.0, temps.of("a"));
        assertNull(temps.of("c"));
        assertEquals("a 60, b 63.5", temps.summary());
        assertSame(AnnealTemps.NONE, AnnealTemps.fromHeaders(headers("a-F", "size=100"), MixGroups.NONE));
    }

    @Test
    void anAssayIsAtOneTemperature() {
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class, () -> AnnealTemps.fromHeaders(
                headers("a-F", "ta=60", "a-R", "ta=58"), MixGroups.NONE));
        assertEquals("Assay a declares ta= twice with different values: 60 on a-F, 58 on a-R", twice.getMessage());
        for (String bad : new String[]{"ta=", "ta=sixty", "ta=101", "ta=-1", "ta=NaN"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> AnnealTemps.fromHeaders(headers("a-F", bad), MixGroups.NONE), bad);
            assertTrue(error.getMessage().startsWith("Primer a-F: cannot read the annealing temperature"),
                    error.getMessage());
        }
    }

    @Test
    void assaysSharingAReactionShareItsTemperature() {
        Map<String, String> descriptions = headers("a-F", "mix=p1 ta=62", "b-F", "mix=p1", "c-F", "ta=55");
        AnnealTemps temps = AnnealTemps.fromHeaders(descriptions, MixGroups.fromHeaders(descriptions));
        assertEquals(Map.of("a", 62.0, "b", 62.0, "c", 55.0), temps.byAssay());

        Map<String, String> differing = headers("a-F", "mix=p1 ta=62", "b-F", "mix=p1 ta=60");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AnnealTemps.fromHeaders(differing, MixGroups.fromHeaders(differing)));
        assertEquals("Assays a and b share the reaction p1 (mix=) but declare different annealing temperatures: ta=62"
                + " and ta=60 (a reaction anneals at one)", error.getMessage());
    }
}
