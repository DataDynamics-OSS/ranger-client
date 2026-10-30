package io.datadynamics.ranger.client.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 400: the server rejected the payload. {@link #getFields()} holds Ranger's messageList, if sent. */
public class RangerValidationException extends RangerException {
    private static final long serialVersionUID = 1L;
    private final transient List<Map<String, Object>> fields;

    public RangerValidationException(String message, ErrorContext ctx, List<Map<String, Object>> fields) {
        super(message, ctx, null);
        this.fields = fields == null ? Collections.emptyList() : fields;
    }

    public List<Map<String, Object>> getFields() {
        return fields;
    }
}
