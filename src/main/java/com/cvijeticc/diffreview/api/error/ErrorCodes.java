package com.cvijeticc.diffreview.api.error;

/** Maps a bare HTTP status to the contract's machine code. */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    public static String forStatus(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 409 -> "idempotency_conflict";
            case 413 -> "payload_too_large";
            case 415 -> "unsupported_media_type";
            case 429 -> "rate_limited";
            default -> status >= 500 ? "internal" : "bad_request";
        };
    }

    public static String messageForStatus(int status) {
        return switch (status) {
            case 400 -> "Malformed request";
            case 401 -> "Missing or invalid bearer token";
            case 403 -> "Forbidden";
            case 404 -> "No such endpoint";
            case 405 -> "Method not allowed for this endpoint";
            case 413 -> "Request payload is too large";
            case 415 -> "Unsupported media type";
            case 429 -> "Rate limit exceeded";
            default -> status >= 500 ? "Internal server error" : "Request could not be processed";
        };
    }
}
