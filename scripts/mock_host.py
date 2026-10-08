#!/usr/bin/env python3
"""
Dead-simple HTTP server that mimics the RuoYi dish-detail API.

WHY
  Two reasons a front-end (or the ApiFox collection) cannot point straight at
  the logical-expiration endpoint:

    1. It lives on a NEW path (/merchant/dish/logical/80), not on
       /merchant/dish/80.
    2. To make logical expiration observable by hand, the value in Redis has to
       be edited (expireTime moved into the past). That means the result body
       changes shape: the logical path answers with the same Dish, but the cache
       it reads from is now a RedisData wrapper.

  This mock lets you route the existing front-end at a fixed host:port without
  touching the front-end code, and it also lets you swap what the endpoint does
  by editing the two handlers below.

USAGE
  py scripts/mock_host.py                # listens on 127.0.0.1:8091
  py scripts/mock_host.py --port 8091

ROUTES
  GET /merchant/dish/{id}          -> normal path  (delegates downstream)
  GET /merchant/dish/logical/{id}  -> logical path (delegates downstream)
  GET /health                      -> liveness probe

NOTE
  This is a LOCAL STUDY TOOL. It forwards to whatever DOWNSTREAM points at,
  and it does NOT log in on your behalf - copy the Authorization header the
  mock receives and it will pass it along unchanged.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8091
DEFAULT_DOWNSTREAM = "http://localhost:8080"

# Proxies must NOT be used for localhost traffic, otherwise requests die with 502.
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def build_handler(downstream):
    class DishMockHandler(BaseHTTPRequestHandler):
        server_version = "DishMock/1.0"

        # Keep the console clean - default logging writes a line per request.
        def log_message(self, fmt, *args):  # noqa: A003
            sys.stderr.write("[mock] %s\n" % (fmt % args))

        def _write_json(self, status, obj):
            payload = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler contract
            path = self.path.split("?", 1)[0]

            if path == "/health":
                self._write_json(200, {"ok": True, "downstream": downstream})
                return

            # /merchant/dish/<id>  or  /merchant/dish/logical/<id>
            if not path.startswith("/merchant/dish/"):
                self._write_json(404, {"code": 404, "msg": "mock only serves /merchant/dish/**"})
                return

            url = downstream.rstrip("/") + self.path
            headers = {"Accept": "application/json"}
            auth = self.headers.get("Authorization")
            if auth:
                headers["Authorization"] = auth

            req = urllib.request.Request(url, headers=headers)
            try:
                with _opener.open(req, timeout=30) as resp:
                    body = resp.read()
                    status = resp.status
            except urllib.error.HTTPError as exc:
                body = exc.read()
                status = exc.code
            except Exception as exc:  # noqa: BLE001
                self._write_json(502, {"code": 502, "msg": "%s: %s" % (type(exc).__name__, exc)})
                return

            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return DishMockHandler


def main():
    parser = argparse.ArgumentParser(description="Local mock that relays dish-detail requests.")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--downstream", default=DEFAULT_DOWNSTREAM,
                        help="where the real app runs (default: %s)" % DEFAULT_DOWNSTREAM)
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), build_handler(args.downstream))
    print("mock listening on http://%s:%d  ->  %s" % (args.host, args.port, args.downstream))
    print("  GET /merchant/dish/80           (mutex path)")
    print("  GET /merchant/dish/logical/80   (logical-expiration path)")
    print("press Ctrl+C to stop")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
