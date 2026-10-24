package com.airtribe.wallet.common;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * An HTTP status plus an already-serialised JSON body. Idempotent endpoints store exactly
 * this pair, so a replay returns the same bytes as the first response.
 */
public record ApiResult(int status, String body) {

    public ResponseEntity<String> toResponse() {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
