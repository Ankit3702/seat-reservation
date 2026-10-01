package com.paytmmoney.seats.metrics;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory counters (same role as the API dashboard counters in the
 * Servosys customer platform). Gauges are computed live from SQL so they
 * always reconcile with GET /shows state.
 */
@Component
public class MetricsRegistry {
    private final AtomicLong confirmed = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> declined = new ConcurrentHashMap<>();

    public void incConfirmed() { confirmed.incrementAndGet(); }
    public void incDeclined(String reason) {
        declined.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
    }
    public long getConfirmed() { return confirmed.get(); }
    public long getDeclined(String reason) {
        AtomicLong a = declined.get(reason);
        return a == null ? 0 : a.get();
    }
    public Map<String, Long> snapshotDeclined() {
        Map<String, Long> m = new java.util.HashMap<>();
        declined.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }
}
