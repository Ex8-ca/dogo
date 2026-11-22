#!/usr/bin/env python3
# Copyright 2026 The Dogo Authors
# SPDX-License-Identifier: Apache-2.0
"""Mints short-lived OpenAI Realtime client secrets for Dogo phones.

The real OpenAI key never leaves this machine. A phone authenticates with the
Hermes API key it already has (API_SERVER_KEY) and gets an ek_... secret that
expires in ten minutes and only works for the Realtime API.

POST /token  {"model": "gpt-realtime-2.1"}   Authorization: Bearer <API_SERVER_KEY>
-> {"value": "ek_...", "expires_at": 1795..., "model": "..."}
"""
import hmac, json, os, sys, time, urllib.error, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ENV = os.path.expanduser(os.environ.get("DOGO_ENV_FILE", "~/.hermes/.env"))
PORT = int(os.environ.get("DOGO_REALTIME_PORT", "8797"))
MODELS = ("gpt-realtime-2.1", "gpt-realtime-2.1-mini", "gpt-realtime", "gpt-realtime-mini")
TTL = 600
MAX_PER_MIN = 10

def env():
    out = {}
    with open(ENV) as f:
        for line in f:
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip().strip("'\"")
    return out

recent = []

class Handler(BaseHTTPRequestHandler):
    server_version = "dogo-realtime/1"

    def log_message(self, fmt, *args):  # no request lines with tokens in them
        sys.stderr.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.reply(200, {"ok": True, "models": MODELS}) if self.path.rstrip("/").endswith(("health", "")) else self.reply(404, {"error": "not found"})

    def do_POST(self):
        if not self.path.rstrip("/").endswith("token"):
            return self.reply(404, {"error": "not found"})
        keys = env()
        expected = keys.get("API_SERVER_KEY", "")
        got = self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        if not expected or not hmac.compare_digest(got.encode(), expected.encode()):
            return self.reply(401, {"error": "Wrong or missing key. Use the same key as Hermes."})
        now = time.time()
        recent[:] = [t for t in recent if now - t < 60]
        if len(recent) >= MAX_PER_MIN:
            return self.reply(429, {"error": "Too many voice sessions started; wait a minute."})
        recent.append(now)
        try:
            n = min(int(self.headers.get("Content-Length") or 0), 4096)
            req = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            req = {}
        model = req.get("model") if req.get("model") in MODELS else MODELS[0]
        body = json.dumps({"expires_after": {"anchor": "created_at", "seconds": TTL},
                           "session": {"type": "realtime", "model": model}}).encode()
        r = urllib.request.Request("https://api.openai.com/v1/realtime/client_secrets", body, method="POST",
                                   headers={"Authorization": "Bearer " + keys.get("OPENAI_API_KEY", ""),
                                            "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(r, timeout=15) as resp:
                d = json.load(resp)
        except urllib.error.HTTPError as e:
            msg = json.loads(e.read() or b"{}").get("error", {}).get("message", "OpenAI refused")
            return self.reply(502, {"error": "OpenAI: " + msg[:200]})
        except Exception as e:
            return self.reply(502, {"error": "Couldn't reach OpenAI: %s" % e})
        self.reply(200, {"value": d["value"], "expires_at": d["expires_at"], "model": model})

if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
