package com.cvijeticc.diffreview.api.error;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ErrorEnvelope {

    private ErrorEnvelope() {
    }

    public static Map<String, Object> of(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }
}
