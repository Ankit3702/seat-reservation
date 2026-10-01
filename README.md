# Seat Reservation at Scale — Java / Spring Boot

JSON API that sells assigned seats with **exactly-once semantics under stampede**:
one winner per hot seat, per-user limits, idempotent retries, token identity.

Stack (maps to my Servosys/ICICI work): **Java 17, Spring Boot 3, `JdbcTemplate` + SQL,
file DB (H2, one-URL swap to Oracle/Postgres), Docker, Maven.**

## Run

```sh
docker compose up --build
# API on http://localhost:8000
```

## API

```sh
# create show (admin-open for the exercise)
curl -s localhost:8000/shows -H 'Content-Type: application/json' -d \
 '{"name":"friday-night","seats":["A1","A2","A12"],"price_paise":25000,"per_user_limit":4}'

# reserve (identity = Bearer token; body user fields ignored)
curl -s localhost:8000/shows/<id>/reserve -H 'Content-Type: application/json' \
 -H 'Authorization: Bearer user-1' -H 'X-Idempotency-Key: k1' \
 -d '{"seats":["A12"],"idempotency_key":"k1"}'
# 201 confirmed | 409 seat-taken / per-user limit / idempotency conflict | 401 no token

# cancel (owner only)
curl -s -XPOST localhost:8000/reservations/<rid>/cancel -H 'Authorization: Bearer user-1'

# state, health, metrics, recent logs
curl -s localhost:8000/shows/<id> | head -c 400
curl -s localhost:8000/healthz; curl -s localhost:8000/readyz; curl -s localhost:8000/metrics
curl -s "localhost:8000/logs?limit=5"
```

Multi-seat is **all-or-nothing**: `["A12","A13"]` with one taken → whole request `409`, nothing held.

## Burst (one command)

```sh
./burst.sh http://localhost:8000
# or: make burst URL=http://localhost:8000
```

Runs a hot-seat storm (500 users × same seat) + random seats + key retries +
per-user-limit probe, prints `confirmed / declined-by-reason / 5xx` and the
`available + held + confirmed == total` reconciliation.

## Deploy

Public URL: **https://seat-reservation-f2a5.onrender.com** — cold-start safe (`/healthz`),
single container, in-memory H2 (`DB_PATH=mem:seats`), `PORT` respected. See `render.yaml`.
Note: free-tier instances sleep when idle; first request can take ~50s (cold start).

## Docs

- `WRITEUP.md` — atomic decision, idempotency, holds, partition behavior,
  2am paging, AI usage, next steps.
