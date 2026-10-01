package com.paytmmoney.seats.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Identity comes ONLY from the Authorization bearer token.
 * Any user_id / userId field in the JSON body is ignored (spoof-proof).
 */
public final class Auth {
    private Auth() {}

    public static String userId(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.length() > 7 && auth.substring(0, 7).equalsIgnoreCase("Bearer ")) {
            String tok = auth.substring(7).trim();
            if (!tok.isEmpty()) return tok;
        }
        String x = req.getHeader("X-User-Id");
        if (x != null && !x.isBlank()) return x.trim();
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bearer token");
    }
}
