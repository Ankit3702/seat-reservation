#!/usr/bin/env python3
"""One-command on-sale stampede. Stdlib only.
Usage: python burst.py <BASE_URL> [--total 2000] [--hot-seat A12] [--hot-n 500]
Runs: hot-seat storm + random seats + idempotent retries + per-user-limit probe,
then prints confirmed/declined-by-reason/5xx + reconciliation.
"""
import json, sys, time, uuid, random, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8000"
args = sys.argv[2:]
def opt(name, default):
    for i, a in enumerate(args):
        if a == name and i + 1 < len(args):
            return args[i + 1]
    return default

TOTAL = int(opt("--total", "1200"))
HOT_SEAT = opt("--hot-seat", "A12")
HOT_N = int(opt("--hot-n", "500"))
SEATS_N = 100

def req(method, path, body=None, user="u", key=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {user}",
                 **({"X-Idempotency-Key": key} if key else {})})
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}"), \
                resp.headers.get("X-Idempotent-Replay") == "true"
    except urllib.error.HTTPError as e:
        try: b = json.loads(e.read().decode() or "{}")
        except Exception: b = {}
        return e.code, b, e.headers.get("X-Idempotent-Replay") == "true"
    except Exception as e:
        return 599, {"error": str(e)}, False

print(f"BASE={BASE} total={TOTAL} hot={HOT_SEAT}x{HOT_N}", flush=True)
st, show, _ = req("POST", "/shows", {"name": "friday-night",
    "seats": [f"A{i}" for i in range(1, SEATS_N + 1)], "price_paise": 25000, "per_user_limit": 4},
    user="admin", key="burst-" + uuid.uuid4().hex[:8])
assert st == 201, (st, show)
SID = show["id"]
print("show:", SID, flush=True)

outcomes = {"confirmed": 0, "seat-taken": 0, "per-user-limit": 0, "replay": 0,
            "same-key-different-body": 0, "other-4xx": 0, "5xx": 0}
first_key = {}  # user -> (key, seats) for retry test
lock = __import__("threading").Lock()

def tally(code, body, replayed=False):
    with lock:
        if replayed: outcomes["replay"] += 1
        elif code == 201: outcomes["confirmed"] += 1
        elif code == 599 or code >= 500: outcomes["5xx"] += 1
        else:
            err = str(body.get("error", ""))
            if "taken" in err: outcomes["seat-taken"] += 1
            elif "limit" in err: outcomes["per-user-limit"] += 1
            elif "conflict" in err or "in use" in err: outcomes["same-key-different-body"] += 1
            else: outcomes["other-4xx"] += 1

def one_hot(i):
    user = f"hotuser-{i}"
    key = "hot-" + uuid.uuid4().hex[:12]
    if i < 30: first_key[user] = (key, [HOT_SEAT])
    c, b, rpl = req("POST", f"/shows/{SID}/reserve", {"seats": [HOT_SEAT], "idempotency_key": key}, user, key)
    tally(c, b, rpl)

def one_rand(i):
    user = f"buyer-{i % 400}"
    seats = random.sample([f"A{i}" for i in range(1, SEATS_N + 1)], random.choice([1, 2]))
    key = "r-" + uuid.uuid4().hex[:12]
    c, b, rpl = req("POST", f"/shows/{SID}/reserve", {"seats": seats, "idempotency_key": key}, user, key)
    tally(c, b, rpl)

t0 = time.time()
with ThreadPoolExecutor(max_workers=200) as ex:
    list(ex.map(one_hot, range(HOT_N)))
    list(ex.map(one_rand, range(TOTAL)))
# idempotent retries: same key + same body -> must not create extra
def one_retry(item):
    user, (key, seats) = item
    c, b, rpl = req("POST", f"/shows/{SID}/reserve", {"seats": seats, "idempotency_key": key}, user, key)
    tally(c, b, rpl)
with ThreadPoolExecutor(max_workers=30) as ex:
    list(ex.map(one_retry, list(first_key.items())[:30]))
# same key different body -> 409
c, b, rpl = req("POST", f"/shows/{SID}/reserve", {"seats": ["A99"], "idempotency_key": list(first_key.values())[0][0]},
           list(first_key.keys())[0], list(first_key.values())[0][0])
tally(c, b, rpl)
# per-user limit probe: one user fires 10 parallel single-seat reserves, must end <= 4
lim_user = "limit-probe-" + uuid.uuid4().hex[:6]
def one_lim(i):
    k = "lim-" + uuid.uuid4().hex[:10]
    seat = f"A{(i % SEATS_N) + 1}"
    c, b, rpl = req("POST", f"/shows/{SID}/reserve", {"seats": [seat], "idempotency_key": k}, lim_user, k)
    return c, b
with ThreadPoolExecutor(max_workers=10) as ex:
    list(ex.map(one_lim, range(10)))
dt = time.time() - t0

st, state, _ = req("GET", f"/shows/{SID}", user="admin")
inv = state.get("available", -1) + state.get("held", -1) + state.get("confirmed", -1)
print(json.dumps({"seconds": round(dt, 1), "outcomes": outcomes,
    "state": {k: state.get(k) for k in ("total_seats", "available", "held", "confirmed")},
    "invariant_sum": inv, "invariant_ok": inv == state.get("total_seats"),
    "metrics_hint": f"{BASE}/metrics"}, indent=2))
print("ZERO_5XX:", outcomes["5xx"] == 0, "| HOT_SEAT_WINNERS<=1 expected: check state")
