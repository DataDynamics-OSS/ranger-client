package io.datadynamics.ranger.demo.web.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.datadynamics.ranger.client.model.RangerJson;
import org.springframework.stereotype.Component;

/**
 * JSON helpers for templates ({@code ${@json.pretty(x)}}) and controllers. Uses the SDK's own mapper so the
 * JSON shown is exactly what goes on the wire (unknown server fields included).
 */
@Component("json")
public class Json {
    private final ObjectMapper pretty = RangerJson.mapper().copy().enable(SerializationFeature.INDENT_OUTPUT);

    public String pretty(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return pretty.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    public <T> T read(String json, Class<T> type) {
        return RangerJson.read(json, type);
    }

    public JsonNode tree(Object value) {
        return RangerJson.tree(value);
    }
}
