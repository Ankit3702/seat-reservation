package com.paytmmoney.seats.web;

import com.paytmmoney.seats.service.ReservationService;
import com.paytmmoney.seats.service.ReservationService.ReserveOutcome;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * JSON API: POST /shows, GET /shows/{id}, POST /shows/{id}/reserve,
 * POST /reservations/{id}/cancel. Money is integer paise.
 */
@RestController
public class ApiController {

    private final ReservationService svc;
    private final JdbcTemplate jdbc;

    public ApiController(ReservationService svc, JdbcTemplate jdbc) {
        this.svc = svc; this.jdbc = jdbc;
    }

    @PostMapping("/shows")
    public ResponseEntity<Map<String, Object>> createShow(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        @SuppressWarnings("unchecked")
        List<String> seats = (List<String>) body.get("seats");
        Object price = body.get("price_paise");
        long pricePaise = price instanceof Number ? ((Number) price).longValue() : -1;
        int limit = 4;
        if (body.get("per_user_limit") instanceof Number)
            limit = ((Number) body.get("per_user_limit")).intValue();
        return ResponseEntity.status(201).body(svc.createShow(name, seats, pricePaise, limit));
    }

    @GetMapping("/shows/{id}")
    public Map<String, Object> showState(@PathVariable("id") String id) {
        return svc.showState(id);
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<Map<String, Object>> reserve(
            @PathVariable("id") String showId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String h1,
            @RequestHeader(value = "Idempotency-Key", required = false) String h2) {
        String userId = Auth.userId(req); // token-derived; body user fields ignored
        @SuppressWarnings("unchecked")
        List<String> seats = (List<String>) body.get("seats");
        String key = h1 != null && !h1.isBlank() ? h1
            : (h2 != null && !h2.isBlank() ? h2 : (String) body.get("idempotency_key"));
        ReserveOutcome o = svc.reserve(showId, userId, seats, key);
        ResponseEntity.BodyBuilder b = ResponseEntity.status(o.status);
        if (o.replay) b = b.header("X-Idempotent-Replay", "true");
        return b.body(o.body);
    }

    @PostMapping("/reservations/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable("id") String id, HttpServletRequest req) {
        return svc.cancel(id, Auth.userId(req));
    }
}
