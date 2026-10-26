package com.robotrac.recruitment.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** The one JSON error format returned by every endpoint. Never contains stack traces or SQL. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(Instant timestamp, int status, String code, String message, String path,
                            List<FieldError> fieldErrors, Object details) {

    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(int status, String code, String message, String path) {
        return new ErrorResponse(Instant.now(), status, code, message, path, null, null);
    }
}
