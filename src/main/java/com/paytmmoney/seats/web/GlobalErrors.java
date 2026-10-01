package com.paytmmoney.seats.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Domain declines (seat-taken, limit, idempotency conflict) are 4xx with a
 * stable JSON body — never 500. Only truly unexpected faults become 500.
 * Same philosophy as the structured exception handling on the loan apps.
 */
@RestControllerAdvice
public class GlobalErrors {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleRse(ResponseStatusException e) {
        HttpStatus s = HttpStatus.resolve(e.getStatusCode().value());
        if (s == null) s = HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = e.getReason() == null ? "error" : e.getReason();
        return ResponseEntity.status(s).body(Map.of("error", reason));
    }

    @ExceptionHandler(Decline.class)
    public ResponseEntity<Map<String, Object>> handleDecline(Decline d) {
        return ResponseEntity.status(d.status).body(d.body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        return ResponseEntity.status(500).body(Map.of("error", "internal"));
    }

    /** A clean domain decline: carries its own 4xx status + body. */
    public static class Decline extends RuntimeException {
        public final int status;
        public final Map<String, Object> body;
        public final String metricReason;
        public final boolean replay;
        public Decline(int status, Map<String, Object> body, String metricReason) {
            this(status, body, metricReason, false);
        }
        public Decline(int status, Map<String, Object> body, String metricReason, boolean replay) {
            super(metricReason);
            this.status = status; this.body = body;
            this.metricReason = metricReason; this.replay = replay;
        }
    }
}
