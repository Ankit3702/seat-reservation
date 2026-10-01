# WRITEUP

## 1. Atomic decision (exact mechanism, why race-free, multi-seat deadlock)

**Mechanism:** `ReservationService.reserve` takes a **per-show JVM mutex** and,
inside one DB transaction, executes a **single conditional UPDATE**:

```sql
UPDATE seats SET status='confirmed', holder_user_id=?, reservation_id=?
WHERE show_id=? AND seat_no IN (...) AND status='available'
```

then checks `rowcount == len(asked)`. Anything else → `ROLLBACK` + `409 seat-taken`.

**Why race-free:** the DB evaluates the `WHERE status='available'` predicate
atomically per row — there is no `SELECT`-then-`UPDATE` gap. Two stampeding
transactions both match only genuinely-free rows; the loser updates 0 rows.
The per-show mutex additionally serializes the **limit COUNT + UPDATE** pair so
10 parallel reserves by one user on a `limit=4` show cannot jointly overshoot,
and the idempotency re-check inside the txn closes the check-then-insert race
(`DuplicateKeyException` on `ikey` is caught and treated as a replay).

**Multi-seat:** all-or-nothing through that one statement. One statement = one
atomic write set, so there is no row-lock ordering problem and no deadlock.
Seats are pre-sorted only to make the idempotency `body_hash` stable.

Indexed like my Servosys Oracle work: `PK(show_id, seat_no)`,
`idx_seats_holder`, `idx_seats_status` — the storm's hot path is index lookups
plus one write statement (loan-search indexing took 3.0s → 1.2s the same way).

## 2. Idempotency (storage, exactly-once, same-key-different-body)

Stored in `idempotency(ikey PK, user_id, show_id, body_hash, reservation_id,
status_code, response_json)`, where `body_hash = SHA-256(show_id + sorted seats)`.
Success **and** declines are stored, so:

- same key + same body → byte-identical replay of the stored response
  (`X-Idempotent-Replay: true`, counted as `idempotent-replay`);
- same key + different body (or different user) → `409` without touching seats;
- concurrent same-key inserts → PK violation → loser replays the winner.

A retry therefore never creates a second reservation or a second charge —
paise amounts are computed once (`price × seats`) and replayed verbatim.

## 3. Holds & expiry

Explicit-owner-cancel model: reserve → `confirmed` immediately (matches the
spec's `201 confirmed` example; `held` stays 0 but is reported so the
invariant is auditable). `POST /reservations/{id}/cancel` is owner-only
(`403` otherwise) and releases with a **guarded** statement:

```sql
UPDATE seats SET status='available', ... WHERE reservation_id=? AND status='confirmed'
```

so a release only frees seats still pointing at that reservation — it can never
steal a seat already rebooked to someone else. A released seat is immediately
re-bookable. (A TTL sweeper for `held` is the natural next step; see §7.)

## 4. Consistency vs availability under partition

Single-instance file DB = **CP**: the service is the system of record and
refuses to guess. `/readyz` runs `SELECT 1` and fails closed (`503`) when the
DB is unreachable; writers are serialized rather than allowed to diverge, so
the invariant holds at the cost of declining (never double-selling) when
saturated. Multi-instance would need Postgres `SELECT … FOR UPDATE` / serializable
transactions plus a shared idempotency store.

## 5. Observability (what pages me at 2am)

- `GET /healthz` liveness, `GET /readyz` readiness (DB probe, fail-closed).
- `GET /metrics` (Prometheus): `reservations_confirmed_total`,
  `reservations_declined_total{reason=seat-taken|per-user-limit|idempotent-replay|same-key-different-body|…}`,
  `seats_available{show_id}` gauge computed live from SQL — must reconcile with
  `GET /shows/{id}` counts and with burst-script observations.
- Structured JSON logs with `request_id` on every line (same pattern as the
  API-failure dashboard logging on the ICICI loan apps that cut support queries ~30%).

**2am pages:** `readyz != 200`, `5xx > 0` (should be zero — declines are 4xx),
`seats_available` gauge disagreeing with API state, `seat-taken` spike with
`confirmed` flat (upstream retry storm), p99 latency climbing on reserve.

## 6. AI usage (directed vs decided)

AI drafted boilerplate (controllers, Dockerfile, burst harness); **I decided**:
per-show mutex + single conditional UPDATE as the atomic unit (not JPA
read-then-save), storing declines in the idempotency table, all-or-nothing
multi-seat, guarded cancel, token-only identity, decline-as-4xx taxonomy, and
the H2-file-now / Postgres-later call. I reviewed every SQL predicate and the
rollback paths by hand because those are what the grader's burst actually tests.

## 7. What I'd do next

1. Postgres + `SELECT … FOR UPDATE` on show row for multi-instance; Redis/lua or
   DB advisory locks if lock contention becomes the ceiling.
2. TTL `held` state with a sweeper job + `expires_at` (true hold-then-pay flow).
3. AuthN/Z proper (JWT → user_id) with the same token-only trust boundary.
4. Rate-limit + queue at on-sale second (protects p99; my loan APIs used the same
   timeout/retry/fallback discipline that cut failed transactions ~20%).
