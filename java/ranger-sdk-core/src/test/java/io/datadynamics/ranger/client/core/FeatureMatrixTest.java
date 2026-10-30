package io.datadynamics.ranger.client.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Feature enum must mirror spec/features.yaml (the file the Python SDK and the tests read). */
class FeatureMatrixTest {
    @Test
    @SuppressWarnings("unchecked")
    void matchesSpec() throws Exception {
        File f = new File(System.getProperty("ranger.sdk.spec.dir", "../../spec"), "features.yaml");
        Map<String, Object> doc = new ObjectMapper(new YAMLFactory()).readValue(f, Map.class);
        Map<String, Map<String, Object>> features = (Map<String, Map<String, Object>>) doc.get("features");
        Set<String> names = new TreeSet<>();
        for (Feature ft : Feature.values()) {
            names.add(ft.name());
        }
        assertEquals(new TreeSet<>(features.keySet()), names);
        for (Feature ft : Feature.values()) {
            Map<String, Object> spec = features.get(ft.name());
            assertEquals(spec.get("server"), ft.server().name().toLowerCase(), ft + ".server");
            assertEquals(spec.get("min_version"), ft.minVersion(), ft + ".min_version");
            assertEquals(spec.get("probe"), ft.probe(), ft + ".probe");
        }
    }
}
