package com.paytmmoney.seats.web;

import com.paytmmoney.seats.metrics.MetricsRegistry;
import com.paytmmoney.seats.service.ReservationService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Health & observability: liveness, readiness (fails closed when DB is
 * down), Prometheus metrics that reconcile with GET /shows state.
 */
@RestController
public class OpsController {

    private final JdbcTemplate jdbc;
    private final MetricsRegistry metrics;
    private final ReservationService svc;
    private final RequestLogBuffer logs;

    public OpsController(JdbcTemplate jdbc, MetricsRegistry metrics, ReservationService svc,
                         RequestLogBuffer logs) {
        this.jdbc = jdbc; this.metrics = metrics; this.svc = svc; this.logs = logs;
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of("service", "seat-reservation", "health", "/healthz",
            "ready", "/readyz", "metrics", "/metrics");
    }

    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        return Map.of("status", "ok");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> readyz() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            if (one != null && one == 1) return ResponseEntity.ok(Map.of("ready", true));
        } catch (Exception ignored) {}
        return ResponseEntity.status(503).body(Map.of("ready", false));
    }

    @GetMapping("/logs")
    public Map<String, Object> logs(@RequestParam(value = "limit", defaultValue = "200") int limit) {
        return Map.of("lines", logs.last(limit));
    }

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public String metrics() {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP reservations_confirmed_total Seats reservations confirmed\n");
        sb.append("# TYPE reservations_confirmed_total counter\n");
        sb.append("reservations_confirmed_total ").append(metrics.getConfirmed()).append("\n");
        sb.append("# HELP reservations_declined_total Declined reservations by reason\n");
        sb.append("# TYPE reservations_declined_total counter\n");
        for (String r : List.of("seat-taken", "per-user-limit", "idempotent-replay",
                "same-key-different-body", "not-found", "forbidden")) {
            sb.append("reservations_declined_total{reason=\"").append(r).append("\"} ")
              .append(metrics.getDeclined(r)).append("\n");
        }
        sb.append("# HELP seats_available Seats currently available\n");
        sb.append("# TYPE seats_available gauge\n");
        try {
            for (String sid : svc.showIds()) {
                sb.append("seats_available{show_id=\"").append(sid).append("\"} ")
                  .append(svc.countAvailable(sid)).append("\n");
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }
}
