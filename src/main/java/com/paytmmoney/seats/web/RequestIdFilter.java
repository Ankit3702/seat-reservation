package com.paytmmoney.seats.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Correlation/request-id + structured JSON logs (the same structured API
 * failure logging used on the ICICI loan apps to cut support queries).
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("seats");
    public static final String REQ_ID = "requestId";
    private final ObjectMapper om = new ObjectMapper();
    private final RequestLogBuffer buffer;

    public RequestIdFilter(RequestLogBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String rid = req.getHeader("X-Request-Id");
        if (rid == null || rid.isBlank()) rid = UUID.randomUUID().toString().substring(0, 12);
        req.setAttribute(REQ_ID, rid);
        long t0 = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", java.time.Instant.now().toString());
            m.put("level", "info");
            m.put("event", "http");
            m.put("request_id", rid);
            m.put("method", req.getMethod());
            m.put("path", req.getRequestURI());
            m.put("status", res.getStatus());
            m.put("latency_ms", System.currentTimeMillis() - t0);
            try {
                String line = om.writeValueAsString(m);
                log.info(line);
                buffer.add(line);
            } catch (Exception ignored) {}
            res.setHeader("X-Request-Id", rid);
        }
    }

    public static String rid(HttpServletRequest req) {
        Object v = req.getAttribute(REQ_ID);
        return v == null ? "-" : v.toString();
    }
}
