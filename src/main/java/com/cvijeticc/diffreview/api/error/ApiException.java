package com.cvijeticc.diffreview.api.error;

/**
 * Carries the HTTP status and the machine code of the error envelope:
 * { "error": { "code": ..., "message": ... } }.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException unauthorized() {
        return new ApiException(401, "unauthorized", "Missing or invalid bearer token");
    }

    public static ApiException payloadTooLarge(int maxBytes) {
        return new ApiException(413, "payload_too_large", "Request payload exceeds " + maxBytes + " bytes");
    }

    public static ApiException invalidJson() {
        return new ApiException(400, "invalid_json", "Request body is not valid JSON");
    }

    public static ApiException invalidDiff(String detail) {
        return new ApiException(422, "invalid_diff", detail);
    }

    public static ApiException invalidOptions(String detail) {
        return new ApiException(422, "invalid_options", detail);
    }

    public static ApiException idempotencyConflict() {
        return new ApiException(409, "idempotency_conflict",
                "Idempotency-Key was already used with a different request body");
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "not_found", what + " not found");
    }
}
