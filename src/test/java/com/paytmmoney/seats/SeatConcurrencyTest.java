package com.paytmmoney.seats;

import com.paytmmoney.seats.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hot-seat storm: 200 threads fight over A12 — exactly one 201, rest 409, zero 500.
 * Same style as the 50 rps load tests on the loan APIs: assert the decline
 * distribution, not just the happy path.
 */
@SpringBootTest
class SeatConcurrencyTest {

    @Autowired ReservationService svc;

    @Test
    void hotSeatExactlyOneWinner() throws Exception {
        List<String> seats = new java.util.ArrayList<>();
        for (int i = 1; i <= 50; i++) seats.add("A" + i);
        Map<String, Object> show = svc.createShow("storm", seats, 25000, 4);
        String sid = (String) show.get("id");

        int n = 200;
        var pool = Executors.newFixedThreadPool(50);
        var start = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger(), declined = new AtomicInteger(), errors = new AtomicInteger();
        ConcurrentHashMap<String, String> winners = new ConcurrentHashMap<>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            pool.submit(() -> {
                try {
                    start.await();
                    var o = svc.reserve(sid, "user-" + k, List.of("A12"), "k-" + k + "-" + UUID.randomUUID());
                    if (o.status == 201) { won.incrementAndGet(); winners.put((String) o.body.get("reservation_id"), "u"); }
                    else if (o.status == 409) declined.incrementAndGet();
                    else errors.incrementAndGet();
                } catch (Exception e) {
                    // Decline-as-exception path also counts as clean decline
                    if (e.getMessage() != null && (e.getMessage().contains("seat-taken"))) declined.incrementAndGet();
                    else errors.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, won.get(), "exactly one winner for A12");
        assertEquals(0, errors.get(), "zero 5xx/unexpected");
        assertEquals(n - 1, declined.get());

        Map<String, Object> st = svc.showState(sid);
        int total = (int) st.get("total_seats");
        long sum = ((Number) st.get("available")).longValue() + ((Number) st.get("held")).longValue()
            + ((Number) st.get("confirmed")).longValue();
        assertEquals(total, sum, "reconciliation invariant");
    }
}
