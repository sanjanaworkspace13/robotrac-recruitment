package com.robotrac.recruitment.exception;

import org.springframework.http.HttpStatus;

/**
 * The attempt is no longer open (submitted or past its deadline). Thrown AFTER the attempt may have been
 * finalised in the same transaction, so service methods declare {@code noRollbackFor} this exception to keep
 * the finalisation.
 */
public class AttemptClosedException extends ApiException {

    public AttemptClosedException(String message) {
        super(HttpStatus.CONFLICT, "ATTEMPT_CLOSED", message);
    }
}
