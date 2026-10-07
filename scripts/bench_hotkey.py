#!/usr/bin/env python3
"""
Hot-key concurrency bench  (Day10 - cache breakdown verification)

WHY
  After a hot key expires, N concurrent requests all miss the cache at the same
  instant and rush to MySQL. That is "cache breakdown" (hua cun ji chuan).
  This script fires N requests simultaneously so you can count the SQL lines in
  the application console afterwards.

  no lock      -> ~N SQL lines
  with mutex   -> 1 SQL line

EXAMPLES
  # easiest: let the script log in for you (captcha is disabled in this project)
  python bench_hotkey.py --flush-key merchant:dish:80
  python bench_hotkey.py --n 50 --c 50 --flush-key merchant:dish:80

  # or pass a token you already have
  python bench_hotkey.py --token eyJhbGci... --flush-key merchant:dish:80

  # other endpoint
  python bench_hotkey.py --url "http://localhost:8080/merchant/dish/hot?top=5"

NOTE ON ENCODING
  Windows cmd uses codepage 936 (GBK). Every string printed by this script is
  plain ASCII on purpose, so it never garbles the console.

NOTE ON PROXY
  urllib honours http_proxy / https_proxy env vars. We disable proxies so that
  requests to localhost are never routed through a proxy (which would answer 502).
"""

import argparse
import json
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_URL = "http://localhost:8080/merchant/dish/80"
DEFAULT_REDIS_CLI = r"D:\Develop\Redis-x64-3.2.100\redis-cli.exe"
DEFAULT_REDIS_DB = "0"

_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http_get(url, token, timeout):
    """Return (status, body, elapsed_ms). status 0 means transport failure."""
    req = urllib.request.Request(
        url,
        headers={"Authorization": "Bearer " + token, "Accept": "application/json"},
    )
    t0 = time.perf_counter()
    try:
        with _opener.open(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        status = exc.code
    except Exception as exc:  # noqa: BLE001 - transport level, keep the message
        body = "%s: %s" % (type(exc).__name__, exc)
        status = 0
    return status, body, (time.perf_counter() - t0) * 1000.0


def worker(idx, url, token, timeout, barrier, results, results_lock):
    barrier.wait()  # all threads released at the same instant
    status, body, ms = http_get(url, token, timeout)
    with results_lock:
        results.append((idx, status, ms, body))


def run_waves(url, token, total, concurrency, timeout):
    results = []
    results_lock = threading.Lock()
    wave_size = max(1, min(concurrency, total))
    offset = 0
    wall_start = time.perf_counter()
    while offset < total:
        size = min(wave_size, total - offset)
        barrier = threading.Barrier(size)
        threads = []
        for i in range(size):
            t = threading.Thread(
                target=worker,
                args=(offset + i, url, token, timeout, barrier, results, results_lock),
            )
            threads.append(t)
            t.start()
        for t in threads:
            t.join()
        offset += size
    return results, (time.perf_counter() - wall_start) * 1000.0


def do_login(base_url, username, password, timeout):
    """POST /login and return the JWT, or None.

    This project has sys.account.captchaEnabled unset -> captcha is OFF,
    so username + password alone is enough.
    """
    payload = json.dumps({"username": username, "password": password}).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + "/login",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    try:
        with _opener.open(req, timeout=timeout) as resp:
            obj = json.loads(resp.read().decode("utf-8", "replace"))
    except Exception as exc:  # noqa: BLE001
        print("[login] request failed: %s" % exc)
        return None
    if obj.get("code") != 200:
        print("[login] server said: %s" % obj.get("msg"))
        return None
    return obj.get("token")


def flush_key(redis_cli, db, key):
    cmd = [redis_cli, "-n", db, "del", key]
    print("[flush] " + " ".join(cmd))
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        out = (proc.stdout or "").strip()
        print("[flush] result: %s" % (out if out else "(no output)"))
    except FileNotFoundError:
        print("[flush] redis-cli not found at: %s" % redis_cli)
        print("[flush] run the del command yourself, then re-run this script.")
    except Exception as exc:  # noqa: BLE001
        print("[flush] failed: %s" % exc)


def main():
    parser = argparse.ArgumentParser(
        description="Fire N concurrent GET requests to verify cache-breakdown handling."
    )
    parser.add_argument("--token", default=None,
                        help="RuoYi JWT token, WITHOUT the 'Bearer ' prefix. "
                             "Omit it and the script will log in instead.")
    parser.add_argument("--username", default="admin", help="login username (default: admin)")
    parser.add_argument("--password", default="admin123", help="login password (default: admin123)")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help="server base url used for /login (default: %s)" % DEFAULT_BASE_URL)
    parser.add_argument("--url", default=DEFAULT_URL, help="target URL (default: %s)" % DEFAULT_URL)
    parser.add_argument("--n", type=int, default=20, help="total requests (default: 20)")
    parser.add_argument("--c", type=int, default=20, help="concurrency per wave (default: 20)")
    parser.add_argument("--timeout", type=float, default=30.0, help="per-request timeout seconds (default: 30)")
    parser.add_argument("--flush-key", default=None,
                        help="redis key to delete right before firing, e.g. merchant:dish:80")
    parser.add_argument("--redis-cli", default=DEFAULT_REDIS_CLI, help="path to redis-cli.exe")
    parser.add_argument("--db", default=DEFAULT_REDIS_DB, help="redis db index (default: 0)")
    args = parser.parse_args()

    token = (args.token or "").strip()
    if token.lower().startswith("bearer "):
        token = token[7:].strip()

    print("=== hot-key concurrency bench ===")
    print("url        : %s" % args.url)
    print("requests   : %d   concurrency: %d" % (args.n, args.c))

    if not token:
        print("[login] no --token given, logging in as %s ..." % args.username)
        token = do_login(args.base_url, args.username, args.password, args.timeout)
        if not token:
            print("!! login failed. Pass --token directly, or check --username/--password.")
            return 1
        print("[login] ok")
    print("token      : (len=%d, prefix=%s...)" % (len(token), token[:12]))
    print("")

    if args.flush_key:
        flush_key(args.redis_cli, args.db, args.flush_key)
        print("")

    print("firing %d requests simultaneously ..." % args.n)
    results, wall_ms = run_waves(args.url, token, args.n, args.c, args.timeout)
    results.sort(key=lambda r: r[0])

    print("")
    print("  #    status   ms        body")
    print("  ---  ------   --------  ----------------------------------------------")
    for idx, status, ms, body in results:
        flat = " ".join(body.split())
        if len(flat) > 62:
            flat = flat[:59] + "..."
        print("  %-3d  %-6s   %8.1f  %s" % (idx + 1, status if status else "ERR", ms, flat))

    ok = [r for r in results if r[1] == 200]
    times = [r[2] for r in results]
    print("")
    print("--- summary ---")
    print("success        : %d / %d" % (len(ok), len(results)))
    if times:
        print("min/median/max : %.1f / %.1f / %.1f ms" % (
            min(times), statistics.median(times), max(times)))
    print("wall clock     : %.1f ms" % wall_ms)

    codes = {}
    for _, status, _, _ in results:
        codes[status] = codes.get(status, 0) + 1
    if len(codes) > 1:
        print("status mix     : %s" % ", ".join("%s x%d" % (k or "ERR", v) for k, v in sorted(codes.items())))

    if any(r[1] == 401 for r in results):
        print("")
        print("!! 401 Unauthorized - the token is expired or missing.")
        print("   RuoYi tokens expire in 30 minutes by default. Log in again and re-run.")
        return 1

    print("")
    print("--- next step ---")
    print("Count the SQL lines printed by MyBatis for these %d requests:" % args.n)
    print("  no lock   : expect about %d  (cache breakdown)" % args.n)
    print("  with lock : expect 1        (only the lock holder queries MySQL)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
