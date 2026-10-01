package com.paytmmoney.seats.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytmmoney.seats.metrics.MetricsRegistry;
import com.paytmmoney.seats.util.Ids;
import com.paytmmoney.seats.web.GlobalErrors.Decline;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE atomic decision lives here.
 *
 * Reserve path (all inside one per-show mutex + one DB transaction):
 *   1. replay check on idempotency key (inside txn — closes check-then-act race)
 *   2. per-user limit COUNT(*) check (inside same mutex+txn — no overshoot)
 *   3. SINGLE conditional UPDATE:
 *        UPDATE seats SET status='confirmed', ...
 *        WHERE show_id=? AND seat_no IN (...) AND status='available'
 *      rowcount == asked  -> exactly one winner; anything else -> ROLLBACK + 409.
 *
 * Multi-seat is all-or-nothing via that one statement, so there is no
 * lock-ordering / deadlock problem (one statement = one atomic write set).
 */
@Service
public class ReservationService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MetricsRegistry metrics;
    private final ObjectMapper om = new ObjectMapper();
    private final ConcurrentHashMap<String, Object> showLocks = new ConcurrentHashMap<>();
    /** Shows are immutable after creation: cache (price, limit) to skip one DB hit per reserve. */
    private final ConcurrentHashMap<String, long[]> showCache = new ConcurrentHashMap<>();

    public ReservationService(JdbcTemplate jdbc, TransactionTemplate tx, MetricsRegistry metrics) {
        this.jdbc = jdbc; this.tx = tx; this.metrics = metrics;
    }

    private Object lockFor(String showId) {
        return showLocks.computeIfAbsent(showId, k -> new Object());
    }

    // ---------------- create show ----------------

    public Map<String, Object> createShow(String name, List<String> seats, long pricePaise, int limit) {
        if (name == null || name.isBlank()) throw new Decline(400, Map.of("error", "name required"), "bad-request");
        if (seats == null || seats.isEmpty()) throw new Decline(400, Map.of("error", "non-empty seats required"), "bad-request");
        if (new HashSet<>(seats).size() != seats.size()) throw new Decline(400, Map.of("error", "duplicate seat names"), "bad-request");
        if (pricePaise < 0) throw new Decline(400, Map.of("error", "price_paise must be integer >= 0"), "bad-request");
        if (limit < 1) throw new Decline(400, Map.of("error", "per_user_limit must be >= 1"), "bad-request");
        String id = Ids.newId(12);
        String now = Ids.nowIso();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO shows(id,name,price_paise,per_user_limit,created_at) VALUES(?,?,?,?,?)",
                id, name, pricePaise, limit, now);
            List<Object[]> batch = new ArrayList<>();
            for (String seat : seats) batch.add(new Object[]{id, seat});
            jdbc.batchUpdate("INSERT INTO seats(show_id,seat_no,status) VALUES(?,?,'available')", batch);
        });
        showCache.put(id, new long[]{pricePaise, limit});
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id); out.put("name", name); out.put("price_paise", pricePaise);
        out.put("per_user_limit", limit); out.put("total_seats", seats.size());
        return out;
    }

    // ---------------- show state ----------------

    public Map<String, Object> showState(String showId) {
        Map<String, Object> show;
        try {
            show = jdbc.queryForMap("SELECT id,name,price_paise,per_user_limit FROM shows WHERE id=?", showId);
        } catch (EmptyResultDataAccessException e) {
            throw new Decline(404, Map.of("error", "show not found"), "not-found");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT seat_no,status,holder_user_id FROM seats WHERE show_id=? ORDER BY seat_no", showId);
        long avail = 0, held = 0, conf = 0;
        List<Map<String, Object>> seatViews = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String st = (String) r.get("STATUS");
            if ("available".equals(st)) avail++;
            else if ("held".equals(st)) held++;
            else conf++;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("seat", r.get("SEAT_NO")); v.put("status", st); v.put("holder", r.get("HOLDER_USER_ID"));
            seatViews.add(v);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", show.get("ID")); out.put("name", show.get("NAME"));
        out.put("price_paise", ((Number) show.get("PRICE_PAISE")).longValue());
        out.put("per_user_limit", ((Number) show.get("PER_USER_LIMIT")).intValue());
        out.put("total_seats", rows.size());
        out.put("available", avail); out.put("held", held); out.put("confirmed", conf);
        out.put("seats", seatViews);
        return out;
    }

    // ---------------- reserve ----------------

    public static class ReserveOutcome {
        public final int status;
        public final Map<String, Object> body;
        public final boolean replay;
        public ReserveOutcome(int status, Map<String, Object> body, boolean replay) {
            this.status = status; this.body = body; this.replay = replay;
        }
    }

    public ReserveOutcome reserve(String showId, String userId, List<String> asked, String key) {
        if (key == null || key.isBlank()) throw new Decline(400, Map.of("error", "idempotency_key required"), "bad-request");
        if (asked == null || asked.isEmpty()) throw new Decline(400, Map.of("error", "seats required"), "bad-request");
        List<String> seats = new ArrayList<>(new TreeSet<>(asked)); // sorted + deduped
        if (seats.size() != asked.size()) throw new Decline(400, Map.of("error", "duplicate seats in request"), "bad-request");
        String bhash = Ids.bodyHash(showId, seats);

        synchronized (lockFor(showId)) {
            final java.util.concurrent.atomic.AtomicBoolean seatTaken = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicBoolean keyRace = new java.util.concurrent.atomic.AtomicBoolean(false);
            ReserveOutcome o = tx.execute(status -> {
                // 1) idempotency inside txn
                List<Map<String, Object>> prior = jdbc.queryForList(
                    "SELECT user_id, body_hash, status_code, response_json FROM idempotency WHERE ikey=?", key);
                if (!prior.isEmpty()) {
                    Map<String, Object> p = prior.get(0);
                    String su = (String) p.get("USER_ID");
                    String sh = (String) p.get("BODY_HASH");
                    int sc = ((Number) p.get("STATUS_CODE")).intValue();
                    if (!userId.equals(su) || !bhash.equals(sh)) {
                        metrics.incDeclined("same-key-different-body");
                        return new ReserveOutcome(409, Map.of("error", "idempotency key conflict"), false);
                    }
                    metrics.incDeclined("idempotent-replay");
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> body = om.readValue((String) p.get("RESPONSE_JSON"),
                            new TypeReference<Map<String, Object>>() {});
                        return new ReserveOutcome(sc, body, true);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }

                long[] cached = showCache.get(showId);
                long price; int limit;
                if (cached != null) { price = cached[0]; limit = (int) cached[1]; }
                else {
                    Map<String, Object> show;
                    try {
                        show = jdbc.queryForMap("SELECT price_paise, per_user_limit FROM shows WHERE id=?", showId);
                    } catch (EmptyResultDataAccessException e) {
                        metrics.incDeclined("not-found");
                        return new ReserveOutcome(404, Map.of("error", "show not found"), false);
                    }
                    price = ((Number) show.get("PRICE_PAISE")).longValue();
                    limit = ((Number) show.get("PER_USER_LIMIT")).intValue();
                    showCache.putIfAbsent(showId, new long[]{price, limit});
                }

                Long heldN = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM seats WHERE show_id=? AND holder_user_id=? AND status IN ('held','confirmed')",
                    Long.class, showId, userId);
                long held = heldN == null ? 0 : heldN;
                if (held + seats.size() > limit) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("error", "per-user limit exceeded");
                    body.put("held", held); body.put("limit", limit);
                    // No seat rows touched: commit the decline key with the txn.
                    storeKey(key, userId, showId, bhash, null, 409, body);
                    metrics.incDeclined("per-user-limit");
                    return new ReserveOutcome(409, body, false);
                }

                // 3) THE atomic decision: one conditional UPDATE, all-or-nothing.
                String newRid = Ids.newId(16);
                String ph = String.join(",", Collections.nCopies(seats.size(), "?"));
                List<Object> args = new ArrayList<>();
                args.add(userId); args.add(newRid); args.add(showId); args.addAll(seats);
                int updated;
                try {
                    updated = jdbc.update(
                        "UPDATE seats SET status='confirmed', holder_user_id=?, reservation_id=? " +
                        "WHERE show_id=? AND seat_no IN (" + ph + ") AND status='available'",
                        args.toArray());
                } catch (Exception e) {
                    // Lock/timeout under burst: take nothing, clean decline (never 500).
                    status.setRollbackOnly();
                    seatTaken.set(true);
                    metrics.incDeclined("seat-taken");
                    return new ReserveOutcome(409, Map.of("error", "seat already taken"), false);
                }
                if (updated != seats.size()) {
                    // Partial match would violate all-or-nothing -> roll the subset back.
                    status.setRollbackOnly();
                    seatTaken.set(true);
                    metrics.incDeclined("seat-taken");
                    return new ReserveOutcome(409, Map.of("error", "seat already taken"), false);
                }

                long amount = price * seats.size();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("reservation_id", newRid); body.put("show_id", showId);
                body.put("user_id", userId); body.put("seats", seats);
                body.put("amount_paise", amount); body.put("status", "confirmed");
                try {
                    jdbc.update("INSERT INTO reservations(id,show_id,user_id,seats_json,amount_paise,status,created_at)" +
                        " VALUES(?,?,?,?,?,?,?)", newRid, showId, userId,
                        toJson(seats), amount, "confirmed", Ids.nowIso());
                    storeKey(key, userId, showId, bhash, newRid, 201, body);
                } catch (DuplicateKeyException dke) {
                    // Same key inserted concurrently -> loser replays the winner.
                    status.setRollbackOnly();
                    keyRace.set(true);
                    metrics.incDeclined("idempotent-replay");
                    return new ReserveOutcome(409, Map.of("error", "seat already taken"), true);
                }
                metrics.incConfirmed();
                return new ReserveOutcome(201, body, false);
            });

            if (keyRace.get()) {
                // Winner's key is committed; replay it (autocommit read, outside rollback).
                List<Map<String, Object>> w = jdbc.queryForList(
                    "SELECT status_code, response_json FROM idempotency WHERE ikey=?", key);
                if (!w.isEmpty()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> rb = om.readValue((String) w.get(0).get("RESPONSE_JSON"),
                            new TypeReference<Map<String, Object>>() {});
                        return new ReserveOutcome(((Number) w.get(0).get("STATUS_CODE")).intValue(), rb, true);
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }
                return o;
            }
            if (seatTaken.get()) {
                // Persist the decline key outside the rolled-back txn so a later
                // same-key-different-body still sees the binding (best effort).
                try {
                    storeKey(key, userId, showId, bhash, null, 409, Map.of("error", "seat already taken"));
                } catch (Exception ignored) {}
            }
            return o;
        }
    }

    private void storeKey(String key, String userId, String showId, String bhash,
                          String rid, int sc, Map<String, Object> body) {
        jdbc.update("INSERT INTO idempotency(ikey,user_id,show_id,body_hash,reservation_id,status_code,response_json,created_at)" +
            " VALUES(?,?,?,?,?,?,?,?)", key, userId, showId, bhash, rid, sc, toJson(body), Ids.nowIso());
    }

    private String toJson(Object o) {
        try { return om.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---------------- cancel ----------------

    public Map<String, Object> cancel(String resId, String userId) {
        Map<String, Object> r;
        try {
            r = jdbc.queryForMap("SELECT show_id, user_id, status FROM reservations WHERE id=?", resId);
        } catch (EmptyResultDataAccessException e) {
            throw new Decline(404, Map.of("error", "reservation not found"), "not-found");
        }
        String showId = (String) r.get("SHOW_ID");
        String owner = (String) r.get("USER_ID");
        if (!userId.equals(owner)) {
            metrics.incDeclined("forbidden");
            throw new Decline(403, Map.of("error", "only owner may cancel"), "forbidden");
        }
        synchronized (lockFor(showId)) {
            tx.executeWithoutResult(s -> {
                String st;
                try {
                    st = jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, resId);
                } catch (EmptyResultDataAccessException e) { st = "cancelled"; }
                if ("cancelled".equals(st)) return;
                // Guarded release: only seats still pointing at THIS reservation
                // are freed — never resurrects someone else's seat.
                jdbc.update("UPDATE seats SET status='available', holder_user_id=NULL, reservation_id=NULL " +
                    "WHERE reservation_id=? AND status='confirmed'", resId);
                jdbc.update("UPDATE reservations SET status='cancelled' WHERE id=?", resId);
            });
        }
        return Map.of("reservation_id", resId, "status", "cancelled");
    }

    public long countAvailable(String showId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE show_id=? AND status='available'",
            Long.class, showId);
        return n == null ? 0 : n;
    }

    public List<String> showIds() {
        return jdbc.queryForList("SELECT id FROM shows", String.class);
    }
}
