#!/usr/bin/env python3
"""Small local receiver for ZEEload structured diagnostic batches."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class ReceiverHandler(BaseHTTPRequestHandler):
    server_version = "ZEEloadLogReceiver/1.0"

    def log_message(self, format: str, *args: object) -> None:
        print(f"[{self.log_date_time_string()}] {format % args}", flush=True)

    def _send_json(self, status: int, payload: dict[str, object]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/health":
            self._send_json(200, {"ok": True, "service": "zeeload-log-receiver"})
            return
        self._send_json(404, {"ok": False, "error": "not_found"})

    def do_POST(self) -> None:
        if self.path != "/logs":
            self._send_json(404, {"ok": False, "error": "not_found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 10 * 1024 * 1024:
                raise ValueError("invalid_content_length")
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(payload, dict) or not isinstance(payload.get("events"), list):
                raise ValueError("events_must_be_array")
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as error:
            self._send_json(400, {"ok": False, "error": str(error)})
            return

        received = dict(payload)
        received["receivedAt"] = datetime.now(timezone.utc).isoformat()
        received["remote"] = self.client_address[0]
        output: Path = self.server.output_path  # type: ignore[attr-defined]
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(received, ensure_ascii=False, separators=(",", ":")))
            stream.write("\n")
        events = received["events"]
        self._send_json(200, {"ok": True, "accepted": len(events)})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=18081)
    parser.add_argument("--output", type=Path, default=Path("logs/remote-diagnostics.jsonl"))
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), ReceiverHandler)
    server.output_path = args.output.resolve()  # type: ignore[attr-defined]
    print(f"listening=http://{args.host}:{args.port}/logs", flush=True)
    print(f"output={server.output_path}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("stopped", flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
